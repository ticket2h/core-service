package com.ticket2h.inventory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.ticket2h.common.config.Ticket2hProperties;

/** Lớp lọc đang dùng theo {@code ticket2h.seat-gate} (ADR-028). {@link RedisSeatGate} luôn có sẵn để đo so sánh. */
@Configuration(proxyBeanMethods = false)
class SeatGateConfig {

    @Bean
    @Primary
    SeatGate seatGate(Ticket2hProperties properties, RedisSeatGate redis) {
        return properties.seatGate() == Ticket2hProperties.SeatGateMode.REDIS ? redis : SeatGate.DATABASE_ONLY;
    }
}
