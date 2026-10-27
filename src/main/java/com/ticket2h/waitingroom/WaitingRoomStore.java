package com.ticket2h.waitingroom;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Hàng chờ của một sự kiện trong Redis (ADR-027). Khoá dùng hash tag {@code {eventId}}: mọi khoá của một sự kiện nằm
 * cùng một node nếu sau này chạy Redis Cluster (script Lua chỉ chạm được khoá cùng node).
 * <ul>
 *   <li>{@code queue}: sorted set, điểm là thứ tự xếp hàng. Đến sau giờ mở bán: số tăng dần (từ 1). Đến trước giờ
 *       mở bán: số ngẫu nhiên trong [0, 1), nên đứng trước mọi người đến sau và xếp ngẫu nhiên với nhau (bấm F5 lúc
 *       19:59:59 không có lợi hơn vào lúc 19:40).</li>
 *   <li>{@code seen}: lần cuối mỗi người hỏi thứ tự; quá {@code stale-after} thì bị bỏ khỏi hàng (đã đóng tab).</li>
 *   <li>{@code admitted}: người đã được vào, điểm là lúc hết hạn vào cửa.</li>
 * </ul>
 * Mọi thao tác là một script Lua: Redis chạy nguyên khối, hai instance core không làm lệch thứ tự hay cho vào trùng.
 */
@Component
class WaitingRoomStore {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> JOIN_OR_STATUS = RedisScript.of("""
            -- KEYS: queue, seen, admitted, seq. ARGV: user, now, join (1/0), presale (1/0), random, ttl
            local now = tonumber(ARGV[2])
            local admittedUntil = redis.call('ZSCORE', KEYS[3], ARGV[1])
            if admittedUntil and tonumber(admittedUntil) > now then
                return {'ADMITTED', admittedUntil, 0}
            end
            local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
            if not rank then
                if ARGV[3] ~= '1' then
                    return {'NOT_IN_QUEUE', '0', redis.call('ZCARD', KEYS[1])}
                end
                local score
                if ARGV[4] == '1' then score = ARGV[5] else score = redis.call('INCR', KEYS[4]) end
                redis.call('ZADD', KEYS[1], score, ARGV[1])
                rank = redis.call('ZRANK', KEYS[1], ARGV[1])
                for i = 1, 4 do redis.call('PEXPIRE', KEYS[i], ARGV[6]) end
            end
            redis.call('ZADD', KEYS[2], now, ARGV[1])
            return {'WAITING', tostring(rank + 1), redis.call('ZCARD', KEYS[1])}
            """, List.class);

    private static final RedisScript<Long> ADMIT = RedisScript.of("""
            -- KEYS: queue, seen, admitted, tick. ARGV: n, now, stale-before, admitted-until, ttl
            if not redis.call('SET', KEYS[4], '1', 'NX', 'PX', 5000) then
                return -1
            end
            local stale = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[3], 'LIMIT', 0, 5000)
            if #stale > 0 then
                redis.call('ZREM', KEYS[1], unpack(stale))
                redis.call('ZREM', KEYS[2], unpack(stale))
            end
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[2])
            local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
            for i = 1, #popped, 2 do
                redis.call('ZADD', KEYS[3], ARGV[4], popped[i])
                redis.call('ZREM', KEYS[2], popped[i])
            end
            if #popped > 0 then
                redis.call('PEXPIRE', KEYS[3], ARGV[5])
            end
            return #popped / 2
            """, Long.class);

    private final StringRedisTemplate redis;

    WaitingRoomStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Vào hàng (nếu chưa có) hoặc chỉ hỏi thứ tự ({@code join = false}). Cả hai đều ghi nhận người này còn đang chờ.
     * {@code ttl}: Redis tự xoá hàng sau ngần này (tính tới sau giờ đóng bán). Thời hạn tương đối, không phải mốc giờ:
     * giờ của app ({@code Clock}) và giờ của Redis không cần khớp nhau.
     */
    Entry joinOrStatus(UUID eventId, UUID userId, boolean join, boolean presale, Instant now, Duration ttl) {
        List<?> result = redis.execute(JOIN_OR_STATUS,
                List.of(key(eventId, "queue"), key(eventId, "seen"), key(eventId, "admitted"), key(eventId, "seq")),
                userId.toString(), String.valueOf(now.toEpochMilli()), join ? "1" : "0", presale ? "1" : "0",
                String.valueOf(ThreadLocalRandom.current().nextDouble()), String.valueOf(ttl.toMillis()));
        String state = (String) result.get(0);
        long value = Long.parseLong((String) result.get(1));
        long waiting = ((Number) result.get(2)).longValue();
        return switch (state) {
            case "ADMITTED" -> new Entry(State.ADMITTED, 0, waiting, Instant.ofEpochMilli(value));
            case "WAITING" -> new Entry(State.WAITING, value, waiting, null);
            default -> new Entry(State.NOT_IN_QUEUE, 0, waiting, null);
        };
    }

    /**
     * Cho {@code n} người đầu hàng vào, mỗi giây (theo {@code now}) chỉ một lần dù bao nhiêu instance cùng chạy job.
     * Trước đó bỏ những người đã quá {@code staleBefore} không hỏi thứ tự. Trả số người được vào, -1 nếu instance khác
     * đã làm cho giây này.
     */
    long admit(UUID eventId, int n, Instant now, Instant staleBefore, Instant admittedUntil, Duration ttl) {
        return redis.execute(ADMIT,
                List.of(key(eventId, "queue"), key(eventId, "seen"), key(eventId, "admitted"),
                        key(eventId, "tick:" + now.getEpochSecond())),
                String.valueOf(n), String.valueOf(now.toEpochMilli()), String.valueOf(staleBefore.toEpochMilli()),
                String.valueOf(admittedUntil.toEpochMilli()), String.valueOf(ttl.toMillis()));
    }

    /** Số người đang chờ và số người đã vào, vé vào cửa còn hạn. */
    Stats stats(UUID eventId, Instant now) {
        Long waiting = redis.opsForZSet().zCard(key(eventId, "queue"));
        Long admitted = redis.opsForZSet().count(key(eventId, "admitted"), now.toEpochMilli() + 1,
                Double.POSITIVE_INFINITY);
        return new Stats(waiting == null ? 0 : waiting, admitted == null ? 0 : admitted);
    }

    static String key(UUID eventId, String part) {
        return "t2h:wr:{" + eventId + "}:" + part;
    }

    enum State { WAITING, ADMITTED, NOT_IN_QUEUE }

    /** {@code position}: 1 là người kế tiếp được vào. {@code admittedUntil}: chỉ có khi đã được vào. */
    record Entry(State state, long position, long waiting, Instant admittedUntil) {}

    record Stats(long waiting, long admitted) {}
}
