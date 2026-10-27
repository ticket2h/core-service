package com.ticket2h.inventory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Nghe thay đổi ghế của mọi sự kiện từ Redis (ADR-024). Chỉ khi chạy web: công cụ sinh dữ liệu không có ai xem.
 * Mất kết nối thì container tự nối lại; message phát lúc mất kết nối bị lỡ, hub nhận ra nhờ số thứ tự.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SeatMapRedisConfig {

    @Bean
    RedisMessageListenerContainer seatChangeListener(RedisConnectionFactory redis, SeatChangeBus bus) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redis);
        container.addMessageListener(bus, new PatternTopic(SeatChangeBus.CHANNEL_PATTERN));
        return container;
    }
}
