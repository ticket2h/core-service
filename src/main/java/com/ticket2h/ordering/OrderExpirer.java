package com.ticket2h.ordering;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.time.Times;

/** Hết hạn MỘT đơn trong transaction riêng. Tách bean khỏi OrderExpiryJob để @Transactional có tác dụng. */
@Component
public class OrderExpirer {

    private final OrderRepository orders;
    private final OrderRelease release;
    private final Clock clock;

    OrderExpirer(OrderRepository orders, OrderRelease release, Clock clock) {
        this.orders = orders;
        this.release = release;
        this.clock = clock;
    }

    /** @return {@code false} nếu đơn đã được trả tiền, huỷ, hoặc đang bị transaction khác khoá */
    @Transactional
    public boolean expireOne(UUID orderId, Instant cutoff) {
        Optional<Order> order = orders.lockExpirable(orderId, Times.utc(cutoff));
        if (order.isEmpty()) {
            return false;
        }
        release.release(order.get(), OrderStatus.EXPIRED, clock.instant());
        return true;
    }
}
