package com.ticket2h.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.ticket2h.common.persistence.AssignedIdEntity;

/** Một phiên thanh toán. {@code id} cũng là {@code merchantRef} gửi sang cổng. Trạng thái đổi qua method. */
@Entity
@Table(name = "payments")
public class Payment extends AssignedIdEntity {

    public static final String PROVIDER_MOCKPAY = "MOCKPAY";

    @Column(updatable = false)
    private UUID orderId;

    @Column(updatable = false)
    private String provider;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    @Column(updatable = false)
    private BigDecimal amount;

    @Column(updatable = false)
    private String currency;

    private String gatewayPaymentId;
    private String gatewayTransactionId;
    private String paymentUrl;
    private Instant expiresAt;
    private Instant succeededAt;
    private String refundReason;
    private int refundAttempts;
    private Instant nextRefundAttemptAt;
    private String lastError;

    @Column(updatable = false)
    private Instant createdAt;

    private Instant updatedAt;

    protected Payment() {}

    private Payment(UUID orderId, String provider, BigDecimal amount, String currency, Instant expiresAt, Instant now) {
        super(UUID.randomUUID());
        this.orderId = orderId;
        this.provider = provider;
        this.status = PaymentStatus.INITIATED;
        this.amount = amount;
        this.currency = currency;
        this.expiresAt = expiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static Payment initiate(UUID orderId, BigDecimal amount, String currency, Instant expiresAt, Instant now) {
        return new Payment(orderId, PROVIDER_MOCKPAY, amount, currency, expiresAt, now);
    }

    /** Lưu phiên cổng thanh toán trả về. Không đổi trạng thái. */
    void attachGatewaySession(String gatewayPaymentId, String paymentUrl, Instant now) {
        this.gatewayPaymentId = gatewayPaymentId;
        this.paymentUrl = paymentUrl;
        this.updatedAt = now;
    }

    /** Gọi cổng thất bại (timeout, 5xx) hoặc cổng báo {@code payment.failed}. */
    void markFailed(String error, Instant now) {
        transition(PaymentStatus.FAILED, now);
        this.lastError = error;
    }

    void markSucceeded(String gatewayTransactionId, Instant now) {
        transition(PaymentStatus.SUCCEEDED, now);
        this.gatewayTransactionId = gatewayTransactionId;
        this.succeededAt = now;
    }

    void markAmountMismatch(String error, Instant now) {
        transition(PaymentStatus.AMOUNT_MISMATCH, now);
        this.lastError = error;
    }

    /** Ghi ý định hoàn tiền. Job hoàn tiền thực hiện sau, ngoài transaction này. */
    void requestRefund(String reason, Instant now) {
        transition(PaymentStatus.REFUND_PENDING, now);
        this.refundReason = reason;
        this.nextRefundAttemptAt = now;
    }

    void markRefunded(Instant now) {
        transition(PaymentStatus.REFUNDED, now);
        this.nextRefundAttemptAt = null;
        this.lastError = null;
    }

    void recordRefundFailure(String error, Instant nextAttemptAt, Instant now) {
        if (status != PaymentStatus.REFUND_PENDING) {
            throw new IllegalStateException("Payment " + getId() + " không ở REFUND_PENDING");
        }
        this.refundAttempts++;
        this.lastError = error;
        this.nextRefundAttemptAt = nextAttemptAt;
        this.updatedAt = now;
    }

    private void transition(PaymentStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalStateException("Không thể chuyển payment từ " + status + " sang " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    boolean isActiveAt(Instant now) {
        return status == PaymentStatus.INITIATED && now.isBefore(expiresAt);
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getProvider() {
        return provider;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getGatewayPaymentId() {
        return gatewayPaymentId;
    }

    public String getGatewayTransactionId() {
        return gatewayTransactionId;
    }

    public String getPaymentUrl() {
        return paymentUrl;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getSucceededAt() {
        return succeededAt;
    }

    public String getRefundReason() {
        return refundReason;
    }

    public int getRefundAttempts() {
        return refundAttempts;
    }

    public Instant getNextRefundAttemptAt() {
        return nextRefundAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
