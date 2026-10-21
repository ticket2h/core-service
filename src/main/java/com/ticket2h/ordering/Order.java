package com.ticket2h.ordering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.ticket2h.common.persistence.AssignedIdEntity;

/**
 * Đơn hàng. Đơn {@code PENDING_PAYMENT} chính là việc giữ chỗ (ADR-001). Trạng thái chỉ đổi qua
 * các method nghiệp vụ, mỗi method kiểm tra {@link OrderStatus#canTransitionTo}. Không có setter.
 */
@Entity(name = "PurchaseOrder")   // "Order" là từ khoá trong JPQL
@Table(name = "orders")
public class Order extends AssignedIdEntity {

    public static final String DEFAULT_CURRENCY = "VND";

    @Column(updatable = false)
    private UUID userId;

    @Column(updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(updatable = false)
    private BigDecimal totalAmount;

    @Column(updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false)
    private SalesChannel salesChannel;

    @Column(updatable = false)
    private String idempotencyKey;

    @Column(updatable = false)
    private String requestHash;

    private Instant expiresAt;
    private Instant paidAt;
    private Instant fulfilledAt;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected Order() {}

    private Order(UUID id, UUID userId, UUID eventId, SalesChannel salesChannel, String idempotencyKey,
            String requestHash, BigDecimal totalAmount, Instant now, Instant expiresAt) {
        super(id);
        this.userId = userId;
        this.eventId = eventId;
        this.salesChannel = salesChannel;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.totalAmount = totalAmount;
        this.currency = DEFAULT_CURRENCY;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static Order pending(UUID userId, UUID eventId, SalesChannel salesChannel, String idempotencyKey,
            String requestHash, BigDecimal totalAmount, Instant now, Instant expiresAt) {
        return new Order(UUID.randomUUID(), userId, eventId, salesChannel, idempotencyKey, requestHash, totalAmount,
                now, expiresAt);
    }

    public void markPaid(Instant now) {
        transition(OrderStatus.PAID, now);
        this.paidAt = now;
    }

    public void markFulfilled(Instant now) {
        transition(OrderStatus.FULFILLED, now);
        this.fulfilledAt = now;
    }

    public void markExpired(Instant now) {
        transition(OrderStatus.EXPIRED, now);
    }

    public void cancel(Instant now) {
        transition(OrderStatus.CANCELLED, now);
    }

    /** Gia hạn giữ chỗ (khi bắt đầu thanh toán). Không bao giờ rút ngắn. */
    public void extendHold(Instant newExpiry, Instant now) {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Chỉ gia hạn được đơn đang chờ thanh toán");
        }
        if (newExpiry.isAfter(expiresAt)) {
            this.expiresAt = newExpiry;
            this.updatedAt = now;
        }
    }

    private void transition(OrderStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalOrderTransitionException(status, next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    public boolean isPayableAt(Instant now) {
        return status == OrderStatus.PENDING_PAYMENT && now.isBefore(expiresAt);
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public SalesChannel getSalesChannel() {
        return salesChannel;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
