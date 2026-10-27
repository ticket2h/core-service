package com.ticket2h.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.audit.AuditAction;
import com.ticket2h.audit.AuditActor;
import com.ticket2h.audit.AuditEntry;
import com.ticket2h.audit.AuditLog;
import com.ticket2h.common.events.OrderPaid;
import com.ticket2h.common.events.Topics;
import com.ticket2h.inventory.SeatHoldService;
import com.ticket2h.ordering.Order;
import com.ticket2h.ordering.OrderService;
import com.ticket2h.ordering.OrderStatus;
import com.ticket2h.outbox.OutboxWriter;
import com.ticket2h.reporting.SalesRollup;

/**
 * Xử lý webhook đã qua kiểm tra chữ ký, trong MỘT transaction. Thứ tự khoá (B3): order → payment →
 * seats. Webhook gửi 3 lần song song: cả 3 chờ nhau ở khoá đơn, lần đầu chuyển SUCCEEDED, hai lần
 * sau thấy payment đã SUCCEEDED nên trả DUPLICATE.
 */
@Component
class PaymentWebhookProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookProcessor.class);
    /** Người làm trong lịch sử thao tác (ADR-023): hệ thống xử lý kết quả thanh toán (webhook hoặc đối soát). */
    static final AuditActor ACTOR = AuditActor.system("payments");

    private final PaymentRepository payments;
    private final OrderService orders;
    private final SeatHoldService seatHolds;
    private final OutboxWriter outbox;
    private final SalesRollup salesRollup;
    private final AuditLog audit;
    private final Clock clock;

    PaymentWebhookProcessor(PaymentRepository payments, OrderService orders, SeatHoldService seatHolds,
            OutboxWriter outbox, SalesRollup salesRollup, AuditLog audit, Clock clock) {
        this.payments = payments;
        this.orders = orders;
        this.seatHolds = seatHolds;
        this.outbox = outbox;
        this.salesRollup = salesRollup;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    WebhookOutcome process(MockPayWebhookEvent event) {
        return switch (event.type() == null ? "" : event.type()) {
            case MockPayWebhookEvent.SUCCEEDED -> succeeded(event);
            case MockPayWebhookEvent.FAILED -> failed(event);
            default -> {
                log.warn("Bỏ qua webhook loại lạ {} (event {})", event.type(), event.eventId());
                yield WebhookOutcome.IGNORED;
            }
        };
    }

    private WebhookOutcome succeeded(MockPayWebhookEvent event) {
        Instant now = clock.instant();
        Optional<Locked> found = lock(event);
        if (found.isEmpty()) {
            return WebhookOutcome.UNKNOWN_PAYMENT;
        }
        Order order = found.get().order();
        Payment payment = found.get().payment();

        if (payment.getStatus().isCaptured()) {
            return WebhookOutcome.DUPLICATE;
        }
        if (payment.getStatus() == PaymentStatus.AMOUNT_MISMATCH) {
            return WebhookOutcome.DUPLICATE;
        }
        if (event.amount() == null || event.amount().compareTo(payment.getAmount()) != 0
                || !payment.getCurrency().equals(event.currency())) {
            log.error("Webhook lệch số tiền: payment {} chờ {} {}, cổng báo {} {}. Cần người kiểm tra.",
                    payment.getId(), payment.getAmount(), payment.getCurrency(), event.amount(), event.currency());
            payment.markAmountMismatch("Cổng báo " + event.amount() + " " + event.currency(), now);
            audit.record(ACTOR, AuditEntry.of(AuditAction.PAYMENT_AMOUNT_MISMATCH, payment.getId())
                    .event(order.getEventId()).detail("orderId", order.getId())
                    .detail("expected", payment.getAmount()).detail("expectedCurrency", payment.getCurrency())
                    .detail("reported", event.amount()).detail("reportedCurrency", event.currency())
                    .detail("source", event.eventId()));
            return WebhookOutcome.AMOUNT_MISMATCH;
        }

        payment.markSucceeded(event.gatewayTransactionId(), now);   // INITIATED hoặc FAILED → SUCCEEDED

        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            // Khoá ghế TRƯỚC khi đếm: đơn có thể đã quá held_until mà job chưa dọn, người khác có thể
            // giữ lại ghế (hết hạn lười) chen vào giữa lúc đếm và lúc chuyển SOLD.
            List<UUID> heldSeats = seatHolds.lockHeldSeats(order.getId());
            long seatItems = orders.items(order.getId()).stream().filter(item -> item.getSeatId() != null).count();
            if (heldSeats.size() == seatItems) {
                int sold = seatHolds.markSold(order.getId());
                if (sold != seatItems) {
                    throw new IllegalStateException("Chuyển SOLD " + sold + "/" + seatItems + " ghế dù đang giữ khoá");
                }
                // Vé GA không cần kiểm tra: chỉ được trả lại khi đơn EXPIRED/CANCELLED, mà đơn đang PENDING và bị ta khoá
                order.markPaid(now);
                outbox.write("Order", order.getId(), Topics.ORDER_PAID, Topics.ORDER_EVENTS,
                        new OrderPaid(order.getId(), order.getUserId(), order.getEventId(), now));
                salesRollup.enqueue(order.getId(), now);   // báo cáo doanh thu (ADR-016), cùng transaction
                return WebhookOutcome.PAID;
            }
            payment.requestRefund("SEATS_TAKEN", now);
            recordRefundRequested(payment, order, "SEATS_TAKEN", event);
            log.warn("Payment {} thành công nhưng đơn {} đã mất ghế: chờ hoàn tiền", payment.getId(), order.getId());
            return WebhookOutcome.REFUND_REQUESTED;
        }

        // Đơn đã EXPIRED/CANCELLED, hoặc đã PAID bằng payment khác (trả hai lần ở hai tab)
        String reason = switch (order.getStatus()) {
            case EXPIRED -> "ORDER_EXPIRED";
            case CANCELLED -> "ORDER_CANCELLED";
            default -> "ORDER_ALREADY_PAID";
        };
        payment.requestRefund(reason, now);
        recordRefundRequested(payment, order, reason, event);
        log.warn("Payment {} thành công nhưng đơn {} đang {}: chờ hoàn tiền", payment.getId(), order.getId(),
                order.getStatus());
        return WebhookOutcome.REFUND_REQUESTED;
    }

    /** {@code source}: id của webhook, hoặc {@code reconcile:<paymentId>} khi kết quả đến từ đối soát. */
    private void recordRefundRequested(Payment payment, Order order, String reason, MockPayWebhookEvent event) {
        audit.record(ACTOR, AuditEntry.of(AuditAction.PAYMENT_REFUND_REQUESTED, payment.getId())
                .event(order.getEventId()).detail("orderId", order.getId()).detail("reason", reason)
                .detail("amount", payment.getAmount()).detail("currency", payment.getCurrency())
                .detail("source", event.eventId()));
    }

    private WebhookOutcome failed(MockPayWebhookEvent event) {
        Optional<Locked> found = lock(event);
        if (found.isEmpty()) {
            return WebhookOutcome.UNKNOWN_PAYMENT;
        }
        Payment payment = found.get().payment();
        if (payment.getStatus() != PaymentStatus.INITIATED) {
            return WebhookOutcome.IGNORED;
        }
        // Đơn vẫn PENDING_PAYMENT để khách tạo phiên thanh toán mới
        payment.markFailed("Cổng báo payment.failed", clock.instant());
        return WebhookOutcome.FAILED_RECORDED;
    }

    /** Khoá đơn TRƯỚC rồi mới khoá payment. */
    private Optional<Locked> lock(MockPayWebhookEvent event) {
        UUID paymentId = parseUuid(event.merchantRef());
        if (paymentId == null) {
            log.warn("Webhook {} có merchantRef không hợp lệ: {}", event.eventId(), event.merchantRef());
            return Optional.empty();
        }
        Optional<UUID> orderId = payments.findOrderIdById(paymentId);   // không khoá, không load entity
        if (orderId.isEmpty()) {
            log.warn("Webhook {} cho payment không tồn tại {}", event.eventId(), paymentId);
            return Optional.empty();
        }
        Order order = orders.lockById(orderId.get()).orElseThrow();
        Payment payment = payments.lockById(paymentId).orElseThrow();
        return Optional.of(new Locked(order, payment));
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    record Locked(Order order, Payment payment) {}
}
