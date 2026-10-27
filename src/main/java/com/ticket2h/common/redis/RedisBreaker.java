package com.ticket2h.common.redis;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Cầu dao cho các lần gọi Redis trên đường đi của request (giới hạn request, số thứ tự sơ đồ ghế, lớp lọc ghế),
 * ADR-026. Redis không trả lời thì mỗi lần gọi chờ hết thời hạn ({@code spring.data.redis.timeout}, 0,5 giây): không
 * có cầu dao, MỌI request chậm thêm chừng ấy cho mỗi lần gọi. Một lần lỗi thì "ngắt" trong {@value #OPEN_SECONDS}
 * giây: các lần gọi trả ngay giá trị dự phòng, không chạm Redis. Hết thời gian thì cho một lần thử; được thì đóng lại.
 * <p>
 * Không dùng cho phòng chờ: ở đó Redis là nơi lưu chính, lỗi thì báo 503 cho người dùng.
 */
@Component
public class RedisBreaker {

    static final int OPEN_SECONDS = 5;
    private static final long OPEN_NANOS = Duration.ofSeconds(OPEN_SECONDS).toNanos();
    private static final Logger log = LoggerFactory.getLogger(RedisBreaker.class);

    /** Thời điểm (System.nanoTime) được thử lại; 0 là đang đóng (gọi bình thường). */
    private final AtomicLong retryAt = new AtomicLong();

    /**
     * Chạy {@code call}; cầu dao đang ngắt hoặc Redis lỗi thì trả {@code fallback}. {@code what}: để ghi log khi
     * ngắt, ví dụ "giới hạn request".
     */
    public <T> T call(String what, Supplier<T> call, Supplier<T> fallback) {
        long until = retryAt.get();
        if (until != 0 && System.nanoTime() < until) {
            return fallback.get();
        }
        try {
            T result = call.get();
            if (until != 0 && retryAt.compareAndSet(until, 0)) {
                log.info("Redis trả lời lại, đóng cầu dao");
            }
            return result;
        } catch (DataAccessException e) {
            long next = System.nanoTime() + OPEN_NANOS;
            if (retryAt.getAndSet(next) == 0) {
                log.warn("Redis không trả lời ({}): bỏ qua Redis trong {} giây: {}", what, OPEN_SECONDS, e.toString());
            }
            return fallback.get();
        }
    }

    /** Đang ngắt (test, log). */
    public boolean isOpen() {
        long until = retryAt.get();
        return until != 0 && System.nanoTime() < until;
    }

    /** Đóng lại ngay: test sau không chịu ảnh hưởng của test trước vừa làm Redis ngừng trả lời. */
    public void reset() {
        retryAt.set(0);
    }
}
