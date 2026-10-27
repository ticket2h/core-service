package com.ticket2h.payment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.time.Times;

/**
 * Các bước có transaction của đối soát (ADR-022). Cột {@code next_reconcile_at}, {@code reconcile_attempts},
 * {@code reconciled_at} do lớp này ghi bằng JDBC; entity {@link Payment} chỉ đặt {@code next_reconcile_at} lúc tạo.
 */
@Component
class PaymentReconciliationSteps {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationSteps.class);

    /** Thời gian "thuê" việc: app sập giữa lúc hỏi cổng thì sau khoảng này payment được hỏi lại. */
    static final Duration LEASE = Duration.ofMinutes(2);

    /**
     * Khoảng chờ trước lần hỏi tiếp theo khi cổng còn báo "đang chờ" hoặc không trả lời. Hết danh sách thì mỗi ngày một
     * lần, tới {@link #GIVE_UP_AFTER} lần (khoảng một tuần) thì thôi hỏi và báo cho người xử lý.
     */
    private static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(2), Duration.ofMinutes(3), Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3), Duration.ofHours(6), Duration.ofHours(12),
            Duration.ofDays(1));
    static final int GIVE_UP_AFTER = 15;

    private final NamedParameterJdbcTemplate jdbc;
    private final PaymentRepository payments;
    private final Clock clock;

    PaymentReconciliationSteps(NamedParameterJdbcTemplate jdbc, PaymentRepository payments, Clock clock) {
        this.jdbc = jdbc;
        this.payments = payments;
        this.clock = clock;
    }

    /**
     * Nhận tối đa {@code limit} payment đến hạn hỏi cổng bằng cách đẩy {@code next_reconcile_at} ra sau {@link #LEASE}.
     * {@code SKIP LOCKED}: hai instance không nhận trùng, và payment đang bị webhook khoá thì để lượt sau.
     */
    @Transactional
    List<UUID> lease(int limit) {
        Instant now = clock.instant();
        return jdbc.queryForList("""
                UPDATE payments SET next_reconcile_at = :leaseUntil
                WHERE id IN (SELECT id FROM payments
                             WHERE reconciled_at IS NULL
                               AND status IN ('INITIATED', 'FAILED')
                               AND next_reconcile_at <= :now
                             ORDER BY next_reconcile_at
                             LIMIT :limit
                             FOR UPDATE SKIP LOCKED)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("leaseUntil", Times.utc(now.plus(LEASE)))
                .addValue("now", Times.utc(now))
                .addValue("limit", limit), UUID.class);
    }

    /** Cổng chưa có kết quả hoặc không trả lời: hẹn lần sau theo {@link #BACKOFF}, quá nhiều lần thì thôi. */
    @Transactional
    void retryLater(UUID paymentId, String reason) {
        Integer attempts = jdbc.queryForObject("""
                UPDATE payments SET reconcile_attempts = reconcile_attempts + 1
                WHERE id = :id
                RETURNING reconcile_attempts
                """, Map.of("id", paymentId), Integer.class);
        int done = attempts == null ? GIVE_UP_AFTER : attempts;
        Instant now = clock.instant();
        if (done >= GIVE_UP_AFTER) {
            jdbc.update("""
                    UPDATE payments SET reconciled_at = :now, next_reconcile_at = NULL, last_error = :error
                    WHERE id = :id
                    """, Map.of("now", Times.utc(now), "id", paymentId,
                    "error", "Ngừng đối soát sau " + done + " lần: " + reason));
            log.error("Ngừng đối soát payment {} sau {} lần, cổng vẫn chưa có kết quả ({}): cần người kiểm tra",
                    paymentId, done, reason);
            return;
        }
        Duration delay = BACKOFF.get(Math.min(done, BACKOFF.size()) - 1);
        jdbc.update("UPDATE payments SET next_reconcile_at = :next WHERE id = :id",
                Map.of("next", Times.utc(now.plus(delay)), "id", paymentId));
    }

    /** Đã có kết quả cuối cùng (thành công, thất bại, hết hạn): không hỏi cổng nữa. */
    @Transactional
    void finish(UUID paymentId) {
        jdbc.update("UPDATE payments SET reconciled_at = :now, next_reconcile_at = NULL WHERE id = :id",
                Map.of("now", Times.utc(clock.instant()), "id", paymentId));
    }

    /**
     * Cổng báo phiên hết hạn, hoặc không có phiên nào (lần gọi tạo phiên chưa tới được cổng): payment còn INITIATED
     * thì chuyển FAILED. Đơn vẫn chờ thanh toán để khách tạo phiên mới (nếu còn hạn giữ chỗ).
     */
    @Transactional
    void closeWithoutPayment(UUID paymentId, String reason) {
        Payment payment = payments.lockById(paymentId).orElseThrow();
        if (payment.getStatus() == PaymentStatus.INITIATED) {
            payment.markFailed(reason, clock.instant());
        }
        finish(paymentId);
    }
}
