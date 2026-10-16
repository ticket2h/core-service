package com.ticket2h.ordering;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.common.error.NotFoundException;

@Service
public class OrderCancellation {

    private final OrderRepository orders;
    private final OrderRelease release;
    private final Clock clock;

    OrderCancellation(OrderRepository orders, OrderRelease release, Clock clock) {
        this.orders = orders;
        this.release = release;
        this.clock = clock;
    }

    /** Khách tự huỷ đơn đang chờ thanh toán. Đơn của người khác → 404, đơn đã trả/hết hạn → 409. */
    @Transactional
    public Order cancel(UUID userId, UUID orderId) {
        Order order = orders.lockByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new NotFoundException("đơn hàng"));
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new BusinessException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "Chỉ huỷ được đơn đang chờ thanh toán (đơn đang " + order.getStatus() + ")");
        }
        release.release(order, OrderStatus.CANCELLED, clock.instant());
        return order;
    }
}
