package com.ticket2h.inventory;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.redis.RedisBreaker;

/**
 * Bản thử giữ ghế bằng Redis (ADR-028), đặt TRƯỚC database chứ không thay database. Mỗi ghế một khoá
 * {@code t2h:seat:{eventId}:<seatId>}: một script Lua kiểm tra mọi ghế trống rồi đặt cả lô (không có chuyện giữ được
 * nửa lô). Lọt qua Redis thì vẫn giữ ghế trong database như bình thường; database từ chối thì trả khoá Redis.
 * <p>
 * Redis và database có thể lệch nhau, theo cả hai chiều, và đều an toàn:
 * <ul>
 *   <li>Redis báo trống, database đã có người (khoá Redis hết hạn trước khi đơn hết hạn, Redis vừa khởi động lại):
 *       database từ chối, khách nhận {@code SEAT_UNAVAILABLE} như không có Redis.</li>
 *   <li>Redis báo có người, database đã trống (đơn huỷ nhưng chưa kịp xoá khoá): khách nhận {@code SEAT_UNAVAILABLE}
 *       sớm, ghế hiện lại sau tối đa thời hạn khoá. Thay đổi sau commit xoá khoá ngay nên hiếm gặp.</li>
 * </ul>
 * Redis không trả lời thì bỏ qua lớp lọc, vào thẳng database ({@link RedisBreaker}: vài giây sau không thử Redis nữa).
 */
@Component
public class RedisSeatGate implements SeatGate {

    private static final String SOLD = "SOLD";
    /** Ghế đã bán giữ khoá lâu: request vào ghế đã bán không bao giờ phải tới database. */
    private static final Duration SOLD_TTL = Duration.ofDays(7);

    /** KEYS: khoá các ghế. ARGV: chủ, thời hạn (ms). Trả vị trí (từ 1) của ghế đầu tiên đã có người, 0 nếu giữ được. */
    private static final RedisScript<Long> CLAIM = RedisScript.of("""
            for i, key in ipairs(KEYS) do
                local holder = redis.call('GET', key)
                if holder and holder ~= ARGV[1] then
                    return i
                end
            end
            for _, key in ipairs(KEYS) do
                redis.call('SET', key, ARGV[1], 'PX', ARGV[2])
            end
            return 0
            """, Long.class);

    /** Trả các khoá còn mang tên chủ này (database đã từ chối). */
    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            local released = 0
            for _, key in ipairs(KEYS) do
                if redis.call('GET', key) == ARGV[1] then
                    redis.call('DEL', key)
                    released = released + 1
                end
            end
            return released
            """, Long.class);


    private final StringRedisTemplate redis;
    private final RedisBreaker breaker;
    private final Duration claimTtl;

    RedisSeatGate(StringRedisTemplate redis, RedisBreaker breaker, Ticket2hProperties properties) {
        this.redis = redis;
        this.breaker = breaker;
        // Bằng thời gian giữ ghế dài nhất trong database (giữ ghế, rồi gia hạn khi bắt đầu thanh toán)
        this.claimTtl = properties.order().holdDuration().plus(properties.order().paymentExtension());
    }

    @Override
    public <T> T guard(UUID eventId, String owner, List<UUID> seatIds, Supplier<T> holdInDatabase) {
        if (seatIds.isEmpty()) {
            return holdInDatabase.get();
        }
        List<String> keys = seatIds.stream().map(id -> key(eventId, id)).toList();
        // Redis không trả lời: -1, bỏ qua lớp lọc, vào thẳng database
        long taken = breaker.call("lớp lọc ghế", () -> {
            Long index = redis.execute(CLAIM, keys, owner, String.valueOf(claimTtl.toMillis()));
            return index == null ? 0L : index;
        }, () -> -1L);
        if (taken < 0) {
            return holdInDatabase.get();
        }
        if (taken > 0) {
            throw new SeatUnavailableException(List.of(seatIds.get((int) taken - 1)));
        }
        try {
            return holdInDatabase.get();
        } catch (RuntimeException e) {
            release(keys, owner);
            throw e;
        }
    }

    /** Lỗi Redis thì bỏ qua: khoá giữ ghế tự hết hạn, ghế đã bán chỉ không được chặn sớm. */
    @Override
    public void committed(UUID eventId, List<SeatChange> changes) {
        breaker.call("lớp lọc ghế", () -> {
            for (SeatChange change : changes) {
                String key = key(eventId, change.seatId());
                switch (change.status()) {
                    case "AVAILABLE" -> redis.delete(key);
                    case SOLD -> redis.opsForValue().set(key, SOLD, SOLD_TTL);
                    default -> { }   // HELD: khoá đã đặt lúc guard
                }
            }
            return null;
        }, () -> null);
    }

    /** Lỗi Redis thì khoá tự hết hạn sau {@code claimTtl}. */
    private void release(List<String> keys, String owner) {
        breaker.call("lớp lọc ghế", () -> redis.execute(RELEASE, keys, owner), () -> 0L);
    }

    static String key(UUID eventId, UUID seatId) {
        return "t2h:seat:{" + eventId + "}:" + seatId;
    }
}
