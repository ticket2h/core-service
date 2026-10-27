package com.ticket2h.payment;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ticket2h.audit.AuditAction;
import com.ticket2h.audit.AuditActor;
import com.ticket2h.audit.AuditEntry;
import com.ticket2h.audit.AuditLog;
import com.ticket2h.payment.MockPayClient.GatewayPayment;
import com.ticket2h.payment.MockPayClient.MockPayException;

/**
 * Đối soát với cổng thanh toán (ADR-022). Webhook có thể không bao giờ đến: khách đã trả tiền mà đơn vẫn chờ thanh
 * toán rồi hết hạn. Job hỏi cổng trạng thái của các payment chưa có kết quả ({@code INITIATED}, {@code FAILED}) từ 2
 * phút sau khi tạo, và xử lý kết quả y như webhook vừa đến ({@link PaymentWebhookProcessor}: cùng thứ tự khoá, cùng
 * cách chống trùng). Webhook thật đến cùng lúc thì một bên ghi nhận, bên kia thấy {@code DUPLICATE}.
 * <p>
 * KHÔNG có {@code @Transactional}: hỏi cổng là gọi mạng, nằm giữa các transaction ngắn của
 * {@link PaymentReconciliationSteps}. Mỗi payment xử lý riêng, một payment lỗi không chặn cả lô.
 */
@Component
public class PaymentReconciliation {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliation.class);
    private static final AuditActor ACTOR = AuditActor.system("payment-reconciliation");
    static final int BATCH_SIZE = 50;

    private final PaymentReconciliationSteps steps;
    private final PaymentWebhookProcessor processor;
    private final MockPayClient mockPay;
    private final AuditLog audit;
    private final Clock clock;

    PaymentReconciliation(PaymentReconciliationSteps steps, PaymentWebhookProcessor processor, MockPayClient mockPay,
            AuditLog audit, Clock clock) {
        this.steps = steps;
        this.processor = processor;
        this.mockPay = mockPay;
        this.audit = audit;
        this.clock = clock;
    }

    /** Một lượt: tối đa {@value #BATCH_SIZE} payment đến hạn. */
    public Report run() {
        Report report = new Report();
        for (UUID paymentId : steps.lease(BATCH_SIZE)) {
            try {
                report.add(reconcile(paymentId));
            } catch (RuntimeException e) {
                log.error("Đối soát payment {} lỗi, thử lại ở lượt sau", paymentId, e);
                report.add(Result.ERROR);
            }
        }
        if (report.paid + report.refundRequested > 0) {
            log.warn("Đối soát: {} payment đã trả tiền mà không có webhook ({} đơn PAID, {} chờ hoàn tiền)",
                    report.paid + report.refundRequested, report.paid, report.refundRequested);
        }
        return report;
    }

    private Result reconcile(UUID paymentId) {
        Optional<GatewayPayment> found;
        try {
            found = mockPay.paymentByMerchantRef(paymentId.toString());
        } catch (MockPayException e) {
            steps.retryLater(paymentId, e.getMessage());
            return Result.GATEWAY_UNAVAILABLE;
        }
        if (found.isEmpty()) {
            steps.closeWithoutPayment(paymentId, "Cổng không có phiên thanh toán này");
            return Result.CLOSED;
        }
        GatewayPayment gateway = found.get();
        String status = gateway.status() == null ? "" : gateway.status();
        switch (status) {
            case GatewayPayment.SUCCEEDED -> {
                WebhookOutcome outcome = processor.process(event(MockPayWebhookEvent.SUCCEEDED, paymentId, gateway));
                steps.finish(paymentId);
                log.info("Đối soát payment {}: cổng báo thành công, {}", paymentId, outcome);
                if (outcome == WebhookOutcome.PAID || outcome == WebhookOutcome.REFUND_REQUESTED) {
                    // Đối soát đã đổi trạng thái thay cho webhook không đến: ghi lại để người vận hành biết
                    audit.recordNow(ACTOR, AuditEntry.of(AuditAction.PAYMENT_RECONCILED, paymentId)
                            .detail("gatewayStatus", status).detail("outcome", outcome.name())
                            .detail("gatewayTransactionId", gateway.gatewayTransactionId()));
                }
                return switch (outcome) {
                    case PAID -> Result.PAID;
                    case REFUND_REQUESTED -> Result.REFUND_REQUESTED;
                    case AMOUNT_MISMATCH -> Result.AMOUNT_MISMATCH;
                    default -> Result.ALREADY_RECORDED;
                };
            }
            case GatewayPayment.FAILED, GatewayPayment.CANCELLED -> {
                processor.process(event(MockPayWebhookEvent.FAILED, paymentId, gateway));
                steps.finish(paymentId);
                return Result.CLOSED;
            }
            case GatewayPayment.EXPIRED -> {
                steps.closeWithoutPayment(paymentId, "Phiên thanh toán hết hạn ở cổng");
                return Result.CLOSED;
            }
            default -> {
                steps.retryLater(paymentId, "Cổng báo " + status);
                return Result.STILL_PENDING;
            }
        }
    }

    /** Kết quả từ cổng được đưa qua đúng đường xử lý của webhook. */
    private MockPayWebhookEvent event(String type, UUID paymentId, GatewayPayment gateway) {
        return new MockPayWebhookEvent("reconcile:" + paymentId, type, gateway.gatewayPaymentId(),
                gateway.gatewayTransactionId(), paymentId.toString(), gateway.amount(), gateway.currency(),
                clock.instant());
    }

    public enum Result {
        /** Cổng báo đã trả tiền, đơn chuyển PAID. */
        PAID,
        /** Cổng báo đã trả tiền nhưng đơn không nhận được nữa (hết hạn, mất ghế): chờ hoàn tiền. */
        REFUND_REQUESTED,
        AMOUNT_MISMATCH,
        /** Webhook đã ghi nhận trước đó. */
        ALREADY_RECORDED,
        /** Thất bại, huỷ, hết hạn, hoặc cổng không có phiên: không hỏi nữa. */
        CLOSED,
        /** Cổng còn báo đang chờ: hỏi lại sau. */
        STILL_PENDING,
        /** Không gọi được cổng: hỏi lại sau. */
        GATEWAY_UNAVAILABLE,
        ERROR
    }

    /** Đếm kết quả của một lượt (log và test). */
    public static final class Report {
        int checked;
        int paid;
        int refundRequested;
        int closed;
        int pending;
        int errors;

        void add(Result result) {
            checked++;
            switch (result) {
                case PAID -> paid++;
                case REFUND_REQUESTED -> refundRequested++;
                case CLOSED, ALREADY_RECORDED, AMOUNT_MISMATCH -> closed++;
                case STILL_PENDING, GATEWAY_UNAVAILABLE -> pending++;
                case ERROR -> errors++;
            }
        }

        public int checked() {
            return checked;
        }

        public int paid() {
            return paid;
        }

        public int refundRequested() {
            return refundRequested;
        }

        public int closed() {
            return closed;
        }

        public int pending() {
            return pending;
        }

        public int errors() {
            return errors;
        }
    }
}
