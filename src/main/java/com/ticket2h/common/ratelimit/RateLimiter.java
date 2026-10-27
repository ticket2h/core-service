package com.ticket2h.common.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ticket2h.common.config.Ticket2hProperties.RateLimit.Bucket;
import com.ticket2h.common.redis.RedisBreaker;

/**
 * Token bucket trên Redis (ADR-026): mỗi khoá một "xô" chứa tối đa {@code capacity} lượt, tự đầy lại
 * {@code refillPerSecond} lượt mỗi giây. Cho phép dồn một lúc (mở trang gọi 5 API cùng lúc) nhưng giới hạn trung bình.
 * <p>
 * Tính toán nằm trong một script Lua: đọc, tính, ghi là một bước nguyên khối, hai instance core cùng trừ một xô không
 * đếm sai. Giờ lấy bằng lệnh {@code TIME} của Redis, không lấy giờ của từng instance (lệch giờ thì xô đầy sai tốc độ).
 * <p>
 * Redis không trả lời thì CHO QUA ({@link RedisBreaker}: vài giây tiếp theo không gọi Redis nữa, request không phải
 * chờ): giới hạn request là lớp bảo vệ thêm, không được biến Redis thành điểm làm sập việc bán vé.
 */
@Component
public class RateLimiter {

    static final String KEY_PREFIX = "t2h:rl:";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> TOKEN_BUCKET = RedisScript.of("""
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local cost = tonumber(ARGV[3])
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'at')
            local tokens = tonumber(state[1]) or capacity
            local at = tonumber(state[2]) or now
            tokens = math.min(capacity, tokens + math.max(0, now - at) * rate / 1000)
            local allowed = 0
            local retry = 0
            if tokens >= cost then
                tokens = tokens - cost
                allowed = 1
            else
                retry = math.ceil((cost - tokens) * 1000 / rate)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'at', now)
            redis.call('PEXPIRE', KEYS[1], math.ceil((capacity - tokens) * 1000 / rate) + 1000)
            return {allowed, retry}
            """, List.class);

    private final StringRedisTemplate redis;
    private final RedisBreaker breaker;

    RateLimiter(StringRedisTemplate redis, RedisBreaker breaker) {
        this.redis = redis;
        this.breaker = breaker;
    }

    /** Lấy một lượt khỏi xô {@code key}. Hết lượt: {@code retryAfter} là lúc có lượt tiếp theo. */
    public Decision tryAcquire(String key, Bucket bucket) {
        return breaker.call("giới hạn request", () -> {
            List<?> result = redis.execute(TOKEN_BUCKET, List.of(KEY_PREFIX + key),
                    String.valueOf(bucket.capacity()), String.valueOf(bucket.refillPerSecond()), "1");
            boolean allowed = ((Number) result.get(0)).longValue() == 1;
            return new Decision(allowed, Duration.ofMillis(((Number) result.get(1)).longValue()));
        }, () -> Decision.ALLOWED);
    }

    public record Decision(boolean allowed, Duration retryAfter) {
        static final Decision ALLOWED = new Decision(true, Duration.ZERO);
    }
}
