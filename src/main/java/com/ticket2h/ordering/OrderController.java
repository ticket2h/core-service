package com.ticket2h.ordering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.common.error.NotFoundException;
import com.ticket2h.common.error.ValidationException;
import com.ticket2h.common.security.AuthUser;

@RestController
class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 64;

    private final OrderPlacement placement;
    private final OrderService orders;
    private final OrderCancellation cancellation;

    OrderController(OrderPlacement placement, OrderService orders, OrderCancellation cancellation) {
        this.placement = placement;
        this.orders = orders;
        this.cancellation = cancellation;
    }

    /** Lần đầu 201. Gửi lại cùng key và cùng nội dung: 200, cùng body, header Idempotent-Replayed: true. */
    @PostMapping("/orders")
    ResponseEntity<OrderResponse> create(AuthUser caller,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_MISSING, "Cần header Idempotency-Key cho mỗi lần mua");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new ValidationException("Idempotency-Key tối đa " + MAX_KEY_LENGTH + " ký tự");
        }
        OrderPlacement.PlacementResult result = placement.place(caller.id(), idempotencyKey, request);
        OrderResponse body = OrderResponse.of(result.order(), result.items());
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/orders/{orderId}")
    OrderResponse get(AuthUser caller, @PathVariable UUID orderId) {
        Order order = orders.findForUser(orderId, caller.id()).orElseThrow(() -> new NotFoundException("đơn hàng"));
        return OrderResponse.of(order, orders.items(orderId));
    }

    @PostMapping("/orders/{orderId}/cancel")
    OrderResponse cancel(AuthUser caller, @PathVariable UUID orderId) {
        Order order = cancellation.cancel(caller.id(), orderId);
        return OrderResponse.of(order, orders.items(orderId));
    }

    @GetMapping("/me/orders")
    List<OrderSummary> mine(AuthUser caller) {
        return orders.recentForUser(caller.id()).stream().map(OrderSummary::of).toList();
    }

    record OrderResponse(UUID orderId, UUID eventId, OrderStatus status, BigDecimal totalAmount, String currency,
            Instant expiresAt, Instant paidAt, List<ItemResponse> items) {
        static OrderResponse of(Order order, List<OrderItem> items) {
            return new OrderResponse(order.getId(), order.getEventId(), order.getStatus(), order.getTotalAmount(),
                    order.getCurrency(), order.getExpiresAt(), order.getPaidAt(),
                    items.stream().map(ItemResponse::of).toList());
        }
    }

    record ItemResponse(UUID ticketTypeId, UUID seatId, int quantity, BigDecimal unitPrice) {
        static ItemResponse of(OrderItem item) {
            return new ItemResponse(item.getTicketTypeId(), item.getSeatId(), item.getQuantity(), item.getUnitPrice());
        }
    }

    record OrderSummary(UUID orderId, UUID eventId, OrderStatus status, BigDecimal totalAmount, Instant createdAt) {
        static OrderSummary of(Order order) {
            return new OrderSummary(order.getId(), order.getEventId(), order.getStatus(), order.getTotalAmount(),
                    order.getCreatedAt());
        }
    }
}
