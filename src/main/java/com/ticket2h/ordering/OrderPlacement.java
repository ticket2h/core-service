package com.ticket2h.ordering;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.ticket2h.common.persistence.Constraints;
import com.ticket2h.inventory.SeatGate;

/**
 * Lớp bọc idempotency quanh {@link OrderCreationService}. KHÔNG có {@code @Transactional}: mỗi lần
 * gọi {@code create} là một transaction riêng ở bean khác. Nếu đặt {@code @Transactional} ở đây thì
 * sau unique violation transaction đã hỏng ("current transaction is aborted"), không đọc lại được.
 */
@Service
public class OrderPlacement {

    static final String IDEMPOTENCY_CONSTRAINT = "uq_orders_user_idempotency_key";

    private final OrderCreationService creation;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final SeatGate seatGate;

    OrderPlacement(OrderCreationService creation, OrderRepository orders, OrderItemRepository orderItems,
            SeatGate seatGate) {
        this.creation = creation;
        this.orders = orders;
        this.orderItems = orderItems;
        this.seatGate = seatGate;
    }

    public PlacementResult place(UUID userId, String idempotencyKey, CreateOrderRequest request) {
        String hash = RequestHash.of(request);
        Optional<Order> existing = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        try {
            // Lớp lọc ghế (ADR-028), mặc định không lọc. Cùng người, cùng key là cùng chủ: request gửi lại không tự
            // chặn mình mà đi tiếp tới database và nhận lại đơn cũ
            List<UUID> seatIds = request.items().stream().filter(CreateOrderRequest.Item::hasSeats)
                    .flatMap(item -> item.seatIds().stream()).toList();
            OrderCreationService.CreatedOrder created = seatGate.guard(request.eventId(), userId + ":" + idempotencyKey,
                    seatIds, () -> creation.create(userId, idempotencyKey, hash, request));
            return new PlacementResult(created.order(), created.items(), false);
        } catch (DataIntegrityViolationException e) {
            if (!Constraints.isUniqueViolation(e, IDEMPOTENCY_CONSTRAINT)) {
                throw e;
            }
            // Request khác cùng key đã commit trước: trả lại đơn của request đó
            Order order = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Trùng idempotency key nhưng không thấy đơn", e));
            return replay(order, hash);
        }
    }

    private PlacementResult replay(Order order, String requestHash) {
        if (!order.getRequestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        return new PlacementResult(order, orderItems.findByOrderId(order.getId()), true);
    }

    public record PlacementResult(Order order, List<OrderItem> items, boolean replayed) {}
}
