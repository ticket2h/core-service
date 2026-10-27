package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.ordering.OrderExpiryJob;
import com.ticket2h.payment.Payment;
import com.ticket2h.payment.PaymentReconciliation;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.Invariants;

/**
 * M6: "đơn kẹt ở trạng thái chờ thanh toán được job đối soát sửa" (ADR-022). Khách trả tiền trên trang của cổng nhưng
 * webhook không bao giờ đến: chỉ đối soát (hỏi lại cổng) mới biết.
 */
class PaymentReconciliationIT extends IntegrationTest {

    /** Lần hỏi cổng đầu tiên: 2 phút sau khi tạo payment. */
    private static final Duration FIRST_CHECK = Duration.ofMinutes(2);

    @Autowired PaymentReconciliation reconciliation;
    @Autowired OrderExpiryJob expiryJob;

    @Test
    void paid_at_the_gateway_without_any_webhook_is_paid_by_reconciliation() {
        Seated s = seatedOrder();
        gateway.customerPaidWithoutWebhook(s.payment.getId().toString());
        clock.advance(FIRST_CHECK);

        PaymentReconciliation.Report report = reconciliation.run();

        assertThat(report.paid()).isEqualTo(1);
        assertThat(paymentStatus(s.payment.getId())).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(s.orderId)).isIn(PaymentWebhookIT.PAID_OR_FULFILLED);
        assertThat(seatStatus(s.seat)).isEqualTo("SOLD");
        assertThat(jdbc.queryForObject("SELECT gateway_transaction_id FROM payments WHERE id = ?", String.class,
                s.payment.getId())).isEqualTo("tx_" + s.payment.getId().toString().replace("-", ""));
        // Đi đúng đường của webhook: có event OrderPaid (phát vé) và đơn vào hàng đợi báo cáo doanh thu
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE order_id = ?", Integer.class, s.orderId) == 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_rollup_queue WHERE order_id = ?", Integer.class,
                s.orderId)).isEqualTo(1);
        Invariants.assertConsistent(jdbc);
    }

    @Test
    void the_gateway_is_not_asked_before_the_normal_webhook_had_its_chance() {
        Seated s = seatedOrder();
        gateway.customerPaidWithoutWebhook(s.payment.getId().toString());
        clock.advance(FIRST_CHECK.minusSeconds(1));

        reconciliation.run();

        assertThat(gateway.statusQueries()).isEmpty();
        assertThat(orderStatus(s.orderId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void order_already_expired_when_the_payment_is_found_gets_a_refund_not_tickets() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.ga(event.id(), ga, 2)).order().getId();
        Payment payment = testData.initiatePayment(buyer, orderId);
        gateway.customerPaidWithoutWebhook(payment.getId().toString());
        gateway.failNextStatusQueries(100);              // cổng tra cứu sập lâu, đơn kịp hết hạn
        clock.advance(Duration.ofMinutes(17));
        reconciliation.run();
        expiryJob.sweep();
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");

        gateway.failNextStatusQueries(0);
        clock.advance(Duration.ofMinutes(5));
        PaymentReconciliation.Report report = reconciliation.run();

        assertThat(report.refundRequested()).isEqualTo(1);
        assertThat(paymentStatus(payment.getId())).isEqualTo("REFUND_PENDING");
        assertThat(orderStatus(orderId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class, ga))
                .isEqualTo(10);
        Invariants.assertConsistent(jdbc);
    }

    @Test
    void still_pending_at_the_gateway_is_asked_again_later() {
        Seated s = seatedOrder();
        clock.advance(FIRST_CHECK);

        assertThat(reconciliation.run().pending()).isEqualTo(1);
        assertThat(reconciliation.run().checked()).as("chưa tới lần hỏi kế tiếp").isZero();
        assertThat(attempts(s.payment.getId())).isEqualTo(1);

        gateway.customerPaidWithoutWebhook(s.payment.getId().toString());
        clock.advance(Duration.ofMinutes(2));
        assertThat(reconciliation.run().paid()).isEqualTo(1);
        assertThat(gateway.statusQueries()).hasSize(2);
        assertThat(orderStatus(s.orderId)).isIn(PaymentWebhookIT.PAID_OR_FULFILLED);
    }

    @Test
    void gateway_outage_leaves_the_payment_for_a_later_round() {
        Seated s = seatedOrder();
        gateway.customerPaidWithoutWebhook(s.payment.getId().toString());
        gateway.failNextStatusQueries(1);
        clock.advance(FIRST_CHECK);

        assertThat(reconciliation.run().pending()).isEqualTo(1);
        assertThat(orderStatus(s.orderId)).isEqualTo("PENDING_PAYMENT");

        clock.advance(Duration.ofMinutes(2));
        assertThat(reconciliation.run().paid()).isEqualTo(1);
    }

    @Test
    void webhook_arriving_while_reconciling_is_recorded_once() throws Exception {
        Seated s = seatedOrder();
        gateway.customerPaidWithoutWebhook(s.payment.getId().toString());
        clock.advance(FIRST_CHECK);
        var webhook = testData.succeededWebhook(s.payment);

        Concurrency.run(2, 2, i -> i == 0 ? reconciliation.run() : testData.sendWebhook(webhook));

        assertThat(paymentStatus(s.payment.getId())).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(s.orderId)).isIn(PaymentWebhookIT.PAID_OR_FULFILLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_rollup_queue WHERE order_id = ?", Integer.class,
                s.orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class, s.orderId, "OrderPaid")).isEqualTo(1);
        Invariants.assertConsistent(jdbc);
    }

    @Test
    void session_that_never_reached_the_gateway_is_closed_and_not_asked_again() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.ga(event.id(), ga, 1)).order().getId();
        gateway.failNextCreates(1);
        assertThatThrownBy(() -> testData.initiatePayment(buyer, orderId)).isInstanceOf(BusinessException.class);
        UUID paymentId = jdbc.queryForObject("SELECT id FROM payments WHERE order_id = ?", UUID.class, orderId);
        clock.advance(FIRST_CHECK);

        assertThat(reconciliation.run().closed()).isEqualTo(1);
        assertThat(paymentStatus(paymentId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT reconciled_at FROM payments WHERE id = ?", OffsetDateTime.class,
                paymentId)).isNotNull();
        assertThat(orderStatus(orderId)).as("đơn vẫn chờ, khách tạo được phiên mới").isEqualTo("PENDING_PAYMENT");

        clock.advance(Duration.ofDays(2));
        reconciliation.run();
        assertThat(gateway.statusQueries()).hasSize(1);
    }

    @Test
    void expired_session_at_the_gateway_lets_the_customer_start_a_new_payment() {
        Seated s = seatedOrder();
        gateway.gatewayStatus(s.payment.getId().toString(), "EXPIRED");
        clock.advance(FIRST_CHECK);

        assertThat(reconciliation.run().closed()).isEqualTo(1);
        assertThat(paymentStatus(s.payment.getId())).isEqualTo("FAILED");

        Payment second = testData.initiatePayment(s.buyer, s.orderId);
        assertThat(second.getId()).isNotEqualTo(s.payment.getId());
    }

    @Test
    void payments_settled_by_their_webhook_are_never_asked() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        testData.paidOrderViaWebhook(testData.user(), CreateOrderRequest.ga(event.id(), ga, 1));
        clock.advance(Duration.ofHours(1));

        assertThat(reconciliation.run().checked()).isZero();
        assertThat(gateway.statusQueries()).isEmpty();
    }

    private Seated seatedOrder() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        UUID seat = testData.seats(event, vip, 1).getFirst();
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.seated(event.id(), vip, List.of(seat)))
                .order().getId();
        return new Seated(seat, buyer, orderId, testData.initiatePayment(buyer, orderId));
    }

    private int attempts(UUID paymentId) {
        return jdbc.queryForObject("SELECT reconcile_attempts FROM payments WHERE id = ?", Integer.class, paymentId);
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

    record Seated(UUID seat, UUID buyer, UUID orderId, Payment payment) {}
}
