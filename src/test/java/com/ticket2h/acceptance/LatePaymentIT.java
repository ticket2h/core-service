package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.ordering.OrderExpirer;
import com.ticket2h.ordering.OrderExpiryJob;
import com.ticket2h.payment.Payment;
import com.ticket2h.payment.RefundJob;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.Invariants;

/** Bước 11: tiền đã trừ nhưng không giao được vé. Chính sách ở ADR-006. */
class LatePaymentIT extends IntegrationTest {

    private static final Duration PAST_HOLD = Duration.ofMinutes(16);   // phiên thanh toán gia hạn giữ chỗ 15 phút

    @Autowired OrderExpiryJob expiryJob;
    @Autowired OrderExpirer expirer;
    @Autowired RefundJob refundJob;

    @Test
    void overdue_but_not_swept_and_seats_intact_is_still_paid() {
        Seated s = seatedOrder();
        clock.advance(PAST_HOLD);

        testData.sendWebhook(testData.succeededWebhook(s.payment));

        assertThat(orderStatus(s.orderId)).isIn(PaymentWebhookIT.PAID_OR_FULFILLED);
        assertThat(seatStatus(s.seat)).isEqualTo("SOLD");
    }

    @Test
    void overdue_and_seat_taken_by_someone_else_requests_refund() {
        Seated a = seatedOrder();
        clock.advance(PAST_HOLD);
        UUID orderB = testData.placeOrder(testData.user(),
                CreateOrderRequest.seated(a.eventId, a.vip, List.of(a.seat))).order().getId();

        testData.sendWebhook(testData.succeededWebhook(a.payment));

        assertThat(paymentStatus(a.payment.getId())).isEqualTo("REFUND_PENDING");
        assertThat(orderStatus(a.orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT order_id FROM seats WHERE id = ?", UUID.class, a.seat)).isEqualTo(orderB);
        assertThat(seatStatus(a.seat)).isEqualTo("HELD");
    }

    @Test
    void webhook_for_expired_order_requests_refund_and_does_not_take_ga_again() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.ga(event.id(), ga, 2)).order().getId();
        Payment payment = testData.initiatePayment(buyer, orderId);
        clock.advance(PAST_HOLD.plusMinutes(1));
        expiryJob.sweep();
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");

        testData.sendWebhook(testData.succeededWebhook(payment));

        assertThat(paymentStatus(payment.getId())).isEqualTo("REFUND_PENDING");
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class, ga))
                .isEqualTo(10);
        Invariants.assertConsistent(jdbc);
    }

    @Test
    void two_successful_payments_pay_the_order_once_and_refund_the_second() {
        Seated s = seatedOrder();
        // Phiên thứ nhất bị đánh dấu FAILED (vd. webhook failed), khách tạo phiên thứ hai, rồi cả hai cùng trả tiền
        testData.sendWebhook(testData.failedWebhook(s.payment));
        Payment second = testData.initiatePayment(s.buyer, s.orderId);

        testData.sendWebhook(testData.succeededWebhook(second));
        testData.sendWebhook(testData.succeededWebhook(s.payment));

        assertThat(orderStatus(s.orderId)).isIn(PaymentWebhookIT.PAID_OR_FULFILLED);
        assertThat(paymentStatus(second.getId())).isEqualTo("SUCCEEDED");
        assertThat(paymentStatus(s.payment.getId())).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT refund_reason FROM payments WHERE id = ?", String.class,
                s.payment.getId())).isEqualTo("ORDER_ALREADY_PAID");
    }

    @Test
    void refund_job_retries_after_gateway_503_and_ends_refunded() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.ga(event.id(), ga, 1)).order().getId();
        Payment payment = testData.initiatePayment(buyer, orderId);
        api.post("/orders/" + orderId + "/cancel", testData.token(buyer), null);   // khách huỷ, rồi tiền vẫn bị trừ
        testData.sendWebhook(testData.succeededWebhook(payment));
        assertThat(paymentStatus(payment.getId())).isEqualTo("REFUND_PENDING");
        gateway.failNextRefunds(2);

        assertThat(refundJob.run()).isZero();          // 503 lần 1
        assertThat(refundJob.run()).isZero();          // chưa tới giờ thử lại: không gọi cổng
        clock.advance(Duration.ofMinutes(3));
        assertThat(refundJob.run()).isZero();          // 503 lần 2
        clock.advance(Duration.ofMinutes(3));
        assertThat(refundJob.run()).isEqualTo(1);      // thành công

        assertThat(paymentStatus(payment.getId())).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT refund_attempts FROM payments WHERE id = ?", Integer.class,
                payment.getId())).isEqualTo(2);
        assertThat(gateway.refundRequests()).hasSize(3)
                .allSatisfy(r -> assertThat(r.get("idempotencyKey").asString()).isEqualTo(payment.getId().toString()));
    }

    /**
     * Đua giữa webhook và job dọn: chỉ có một trong hai kết quả hợp lệ, không bao giờ nửa vời.
     * Webhook đi qua HTTP nên chậm hơn lời gọi trực tiếp; trễ ngẫu nhiên vài ms cho job để hai thứ tự đều xảy ra.
     */
    @Test
    void webhook_racing_the_expiry_job_never_leaves_a_half_state() throws Exception {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        var random = new java.util.Random(42);
        int paid = 0;
        int expired = 0;
        for (int round = 0; round < 50; round++) {
            UUID seat = testData.seats(event, vip, 1).getFirst();
            UUID buyer = testData.user();
            UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.seated(event.id(), vip, List.of(seat)))
                    .order().getId();
            Payment payment = testData.initiatePayment(buyer, orderId);
            var webhook = testData.succeededWebhook(payment);
            clock.advance(PAST_HOLD.plusMinutes(1));
            var cutoff = clock.instant().minus(Duration.ofMinutes(1));
            long jitterMs = random.nextInt(15);

            Concurrency.run(2, 2, i -> {
                if (i == 0) {
                    sleep(jitterMs);
                    return expirer.expireOne(orderId, cutoff);
                }
                return testData.sendWebhook(webhook).status();
            });

            String order = orderStatus(orderId);
            String pay = paymentStatus(payment.getId());
            String seatState = seatStatus(seat);
            if (order.equals("EXPIRED")) {
                assertThat(pay).isEqualTo("REFUND_PENDING");
                assertThat(seatState).isEqualTo("AVAILABLE");
                expired++;
            } else {
                assertThat(order).isIn(PaymentWebhookIT.PAID_OR_FULFILLED);
                assertThat(pay).isEqualTo("SUCCEEDED");
                assertThat(seatState).isEqualTo("SOLD");
                paid++;
            }
        }
        System.out.printf("Đua webhook/job dọn: %d lần PAID, %d lần EXPIRED%n", paid, expired);
        Invariants.assertConsistent(jdbc);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Seated seatedOrder() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        UUID seat = testData.seats(event, vip, 1).getFirst();
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.seated(event.id(), vip, List.of(seat)))
                .order().getId();
        return new Seated(event.id(), vip, seat, buyer, orderId, testData.initiatePayment(buyer, orderId));
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private String paymentStatus(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private String seatStatus(UUID seatId) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seatId);
    }

    record Seated(UUID eventId, UUID vip, UUID seat, UUID buyer, UUID orderId, Payment payment) {}
}
