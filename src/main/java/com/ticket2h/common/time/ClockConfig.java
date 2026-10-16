package com.ticket2h.common.time;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Nguồn thời gian duy nhất của ứng dụng. Code nghiệp vụ lấy {@code clock.instant()} rồi truyền
 * vào SQL làm tham số, không dùng {@code now()} của PostgreSQL. Test thay bằng MutableClock.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Làm tròn xuống micro giây: {@code timestamptz} chỉ lưu đến micro giây. Nếu Java so sánh ở nano giây
     * còn SQL so sánh ở micro giây, hai bên có thể kết luận khác nhau về cùng một mốc thời gian.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }
}
