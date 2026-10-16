package com.ticket2h.payment;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.common.error.NotFoundException;
import com.ticket2h.inventory.SeatHoldService;
import com.ticket2h.ordering.Order;
import com.ticket2h.ordering.OrderService;

/** Hai transaction ngắn kẹp lời gọi MockPay ở {@link PaymentInitiation}. */
@Component
class PaymentInitiationSteps {

    private final OrderService orders;
    private final PaymentRepository payments;
    private final SeatHoldService seatHolds;
    private final Ticket2hProperties properties;
    private final Clock clock;

    PaymentInitiationSteps(OrderService orders, PaymentRepository payments, SeatHoldService seatHolds,
            Ticket2hProperties properties, Clock clock) {
        this.orders = orders;
        this.payments = payments;
        this.seatHolds = seatHolds;
        this.properties = properties;
        this.clock = clock;
    }

    /** Transaction 1: khoá đơn, trả payment đang dùng được hoặc tạo payment mới và gia hạn giữ chỗ. */
    @Transactional
    Prepared prepare(UUID userId, UUID orderId) {
        Instant now = clock.instant();
        Order order = orders.lockForUser(orderId, userId).orElseThrow(() -> new NotFoundException("đơn hàng"));
        if (!order.isPayableAt(now)) {
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE,
                    "Đơn đã hết hạn, đã huỷ hoặc đã thanh toán (đơn đang " + order.getStatus() + ")");
        }

        Optional<Payment> active = payments.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .filter(p -> p.isActiveAt(now))
                .max(Comparator.comparing(Payment::getExpiresAt));
        if (active.isPresent()) {
            // Idempotent: bấm "thanh toán" hai lần thì dùng lại phiên cũ
            return new Prepared(active.get(), false);
        }
        if (payments.countByOrderId(orderId) >= properties.order().maxPaymentAttempts()) {
            throw new BusinessException(ErrorCode.PAYMENT_ATTEMPTS_EXCEEDED,
                    "Đơn đã tạo tối đa " + properties.order().maxPaymentAttempts() + " phiên thanh toán");
        }

        // Gia hạn bằng thời gian sống của phiên thanh toán: bấm thanh toán ở phút thứ 9 không bị mất chỗ
        Instant extended = now.plus(properties.order().paymentExtension());
        Instant newExpiry = extended.isAfter(order.getExpiresAt()) ? extended : order.getExpiresAt();
        order.extendHold(newExpiry, now);

        // Thứ tự khoá (B3): order → (INSERT payment mới) → seats
        Payment payment = payments.saveAndFlush(
                Payment.initiate(orderId, order.getTotalAmount(), order.getCurrency(), newExpiry, now));
        seatHolds.extend(orderId, newExpiry);
        return new Prepared(payment, true);
    }

    /** Transaction 2 (thành công): lưu phiên MockPay trả về. */
    @Transactional
    Payment recordSession(UUID paymentId, String gatewayPaymentId, String paymentUrl) {
        Payment payment = payments.lockById(paymentId).orElseThrow();
        payment.attachGatewaySession(gatewayPaymentId, paymentUrl, clock.instant());
        return payment;
    }

    /** Transaction 2 (thất bại): payment FAILED, đơn vẫn PENDING_PAYMENT để khách thử lại. */
    @Transactional
    void recordGatewayFailure(UUID paymentId, String error) {
        Payment payment = payments.lockById(paymentId).orElseThrow();
        if (payment.getStatus() == PaymentStatus.INITIATED) {
            payment.markFailed(error, clock.instant());
        }
    }

    record Prepared(Payment payment, boolean created) {
        boolean needsGatewayCall() {
            return payment.getPaymentUrl() == null;
        }
    }
}
