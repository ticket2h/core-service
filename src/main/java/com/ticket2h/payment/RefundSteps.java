package com.ticket2h.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.audit.AuditAction;
import com.ticket2h.audit.AuditActor;
import com.ticket2h.audit.AuditEntry;
import com.ticket2h.audit.AuditLog;
import com.ticket2h.common.time.Times;

/** Các transaction ngắn của job hoàn tiền. Lời gọi MockPay nằm giữa, ngoài transaction. */
@Component
class RefundSteps {

    private static final Logger log = LoggerFactory.getLogger(RefundSteps.class);

    /** Thời gian "thuê" việc: app sập giữa chừng thì sau khoảng này payment được thử lại. */
    static final Duration LEASE = Duration.ofMinutes(2);
    static final int MANUAL_ATTENTION_AFTER = 8;
    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5),
            Duration.ofMinutes(10), Duration.ofMinutes(30), Duration.ofHours(1));

    private static final AuditActor ACTOR = AuditActor.system("refund-job");

    private final NamedParameterJdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final AuditLog audit;
    private final Clock clock;

    RefundSteps(NamedParameterJdbcTemplate jdbc, PaymentRepository payments, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Nhận tối đa {@code limit} payment cần hoàn bằng cách đẩy {@code next_refund_attempt_at} ra sau
     * {@link #LEASE}. {@code SKIP LOCKED}: hai instance không nhận trùng việc.
     */
    @Transactional
    List<Lease> lease(int limit) {
        Instant now = clock.instant();
        return jdbc.query("""
                UPDATE payments
                SET next_refund_attempt_at = :leaseUntil, updated_at = :now
                WHERE id IN (SELECT id FROM payments
                             WHERE status = 'REFUND_PENDING'
                               AND (next_refund_attempt_at IS NULL OR next_refund_attempt_at <= :now)
                             ORDER BY created_at
                             LIMIT :limit
                             FOR UPDATE SKIP LOCKED)
                RETURNING id, gateway_transaction_id, amount
                """, new MapSqlParameterSource()
                .addValue("leaseUntil", Times.utc(now.plus(LEASE)))
                .addValue("now", Times.utc(now))
                .addValue("limit", limit),
                (rs, i) -> new Lease(rs.getObject("id", UUID.class), rs.getString("gateway_transaction_id"),
                        rs.getBigDecimal("amount")));
    }

    @Transactional
    void markRefunded(UUID paymentId) {
        Payment payment = payments.lockById(paymentId).orElseThrow();
        if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
            payment.markRefunded(clock.instant());
            audit.record(ACTOR, AuditEntry.of(AuditAction.PAYMENT_REFUNDED, paymentId)
                    .detail("orderId", payment.getOrderId()).detail("reason", payment.getRefundReason())
                    .detail("amount", payment.getAmount()).detail("currency", payment.getCurrency()));
        }
    }

    @Transactional
    void recordFailure(UUID paymentId, String error) {
        Instant now = clock.instant();
        Payment payment = payments.lockById(paymentId).orElseThrow();
        if (payment.getStatus() != PaymentStatus.REFUND_PENDING) {
            return;
        }
        int attempts = payment.getRefundAttempts() + 1;
        Duration delay = BACKOFF.get(Math.min(attempts, BACKOFF.size()) - 1);
        payment.recordRefundFailure(error, now.plus(delay), now);
        if (attempts >= MANUAL_ATTENTION_AFTER) {
            log.error("Hoàn tiền payment {} thất bại {} lần, cần xử lý tay: {}", paymentId, attempts, error);
        } else {
            log.warn("Hoàn tiền payment {} thất bại lần {}, thử lại sau {}: {}", paymentId, attempts, delay, error);
        }
    }

    record Lease(UUID paymentId, String gatewayTransactionId, BigDecimal amount) {}
}
