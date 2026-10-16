package com.ticket2h.ordering;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.time.Times;

/** API công khai của module ordering cho payment và ticketing. */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final OrderItemRepository orderItems;

    OrderService(OrderRepository orders, OrderItemRepository orderItems) {
        this.orders = orders;
        this.orderItems = orderItems;
    }

    /** {@code SELECT ... FOR UPDATE}. Luôn khoá đơn trước payment, ghế và vé (B3). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Order> lockById(UUID orderId) {
        return orders.lockById(orderId);
    }

    /** Khoá đơn của đúng user; đơn của người khác trả rỗng (→ 404). */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Order> lockForUser(UUID orderId, UUID userId) {
        return orders.lockByIdAndUserId(orderId, userId);
    }

    @Transactional(readOnly = true)
    public List<OrderItem> items(UUID orderId) {
        return orderItems.findByOrderId(orderId).stream()
                .sorted(Comparator.comparing(OrderItem::getTicketTypeId)
                        .thenComparing(item -> item.getSeatId() == null ? "" : item.getSeatId().toString()))
                .toList();
    }

    /** Đơn PAID từ trước {@code cutoff} mà vẫn chưa FULFILLED (message phát vé bị mất hoặc vào DLT). */
    @Transactional(readOnly = true)
    public List<UUID> paidButNotFulfilledBefore(Instant cutoff, int limit) {
        return orders.findPaidNotFulfilledIds(Times.utc(cutoff), limit);
    }

    @Transactional(readOnly = true)
    public Optional<Order> findForUser(UUID orderId, UUID userId) {
        return orders.findByIdAndUserId(orderId, userId);
    }

    @Transactional(readOnly = true)
    public List<Order> recentForUser(UUID userId) {
        return orders.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }
}
