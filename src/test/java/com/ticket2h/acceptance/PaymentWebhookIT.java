package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.ordering.CreateOrderRequest.Item;
import com.ticket2h.payment.Payment;
import com.ticket2h.support.Api;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.Invariants;

/** Bước 10: webhook thanh toán. Test tự ký và POST, không cần MockPay thật. */
class PaymentWebhookIT extends IntegrationTest {

    /** Relay và consumer chạy nền: đơn vừa PAID có thể đã được phát vé ngay sau đó. */
    static final String[] PAID_OR_FULFILLED = {"PAID", "FULFILLED"};

    @Test
    void valid_success_webhook_marks_order_paid_and_seats_sold() {
        Fixture f = fixture();

        Api.Response response = testData.sendWebhook(testData.succeededWebhook(f.payment));

        assertThat(response.status()).isEqualTo(200);
        assertThat(orderStatus(f)).isIn(PAID_OR_FULFILLED);
        assertThat(paymentStatus(f.payment.getId())).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForList("SELECT status FROM seats WHERE order_id = ?", String.class, f.orderId))
                .containsExactly("SOLD", "SOLD");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'OrderPaid'",
                Integer.class)).isEqualTo(1);
        Invariants.assertConsistent(jdbc);
    }

    @Test
    void same_webhook_sent_three_times_in_a_row_transitions_once() {
        Fixture f = fixture();
        Map<String, Object> webhook = testData.succeededWebhook(f.payment);

        for (int i = 0; i < 3; i++) {
            assertThat(testData.sendWebhook(webhook).status()).isEqualTo(200);
        }

        assertExactlyOnePayment(f);
    }

    @Test
    void same_webhook_sent_three_times_in_parallel_transitions_once() throws Exception {
        Fixture f = fixture();
        Map<String, Object> webhook = testData.succeededWebhook(f.payment);

        List<Integer> statuses = Concurrency.run(3, 3, i -> testData.sendWebhook(webhook).status());

        assertThat(statuses).containsOnly(200);
        assertExactlyOnePayment(f);
    }

    @Test
    void wrong_signature_is_401_changes_nothing_and_is_logged() throws Exception {
        Fixture f = fixture();
        byte[] body = json.writeValueAsBytes(testData.succeededWebhook(f.payment));

        Api.Response response = testData.sendSignedWebhook(body, "attacker-secret", "evt_fake");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.code()).isEqualTo("INVALID_WEBHOOK_SIGNATURE");
        assertThat(orderStatus(f)).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentStatus(f.payment.getId())).isEqualTo("INITIATED");
        assertThat(jdbc.queryForObject("SELECT signature_valid FROM payment_webhook_logs", Boolean.class)).isFalse();
    }

    @Test
    void amount_mismatch_leaves_order_pending_and_flags_payment() {
        Fixture f = fixture();
        Map<String, Object> webhook = testData.webhook("payment.succeeded", f.payment.getId(),
                f.payment.getGatewayPaymentId(), "tx_1", new BigDecimal("1000"), "VND");

        assertThat(testData.sendWebhook(webhook).status()).isEqualTo(200);

        assertThat(orderStatus(f)).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentStatus(f.payment.getId())).isEqualTo("AMOUNT_MISMATCH");
        assertThat(jdbc.queryForList("SELECT status FROM seats WHERE order_id = ?", String.class, f.orderId))
                .containsOnly("HELD");
    }

    @Test
    void same_amount_with_different_scale_is_accepted() {
        Fixture f = fixture();
        Map<String, Object> webhook = testData.succeededWebhook(f.payment);
        webhook.put("amount", new BigDecimal("4600000"));   // payment lưu 4600000.00: so bằng compareTo

        testData.sendWebhook(webhook);

        assertThat(orderStatus(f)).isIn(PAID_OR_FULFILLED);
    }

    @Test
    void failed_webhook_marks_payment_failed_and_customer_can_retry() {
        Fixture f = fixture();

        assertThat(testData.sendWebhook(testData.failedWebhook(f.payment)).status()).isEqualTo(200);

        assertThat(paymentStatus(f.payment.getId())).isEqualTo("FAILED");
        assertThat(orderStatus(f)).isEqualTo("PENDING_PAYMENT");
        Api.Response retry = api.post("/orders/" + f.orderId + "/payments", testData.token(f.buyer), null);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.json().get("paymentId").asString()).isNotEqualTo(f.payment.getId().toString());
    }

    @Test
    void failed_then_succeeded_out_of_order_ends_paid() {
        Fixture f = fixture();

        testData.sendWebhook(testData.failedWebhook(f.payment));
        testData.sendWebhook(testData.succeededWebhook(f.payment));

        assertThat(paymentStatus(f.payment.getId())).isEqualTo("SUCCEEDED");
        assertThat(orderStatus(f)).isIn(PAID_OR_FULFILLED);
    }

    @Test
    void return_url_never_changes_order_state() {
        Fixture f = fixture();

        Api.Response response = api.get("/payments/return?merchantRef=" + f.payment.getId() + "&status=SUCCESS", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(orderStatus(f)).isEqualTo("PENDING_PAYMENT");
        assertThat(paymentStatus(f.payment.getId())).isEqualTo("INITIATED");
    }

    @Test
    void unknown_merchant_ref_is_200_so_gateway_stops_retrying() {
        Map<String, Object> webhook = testData.webhook("payment.succeeded", UUID.randomUUID(), "mp_x", "tx_x",
                new BigDecimal("1"), "VND");

        assertThat(testData.sendWebhook(webhook).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT result FROM payment_webhook_logs", String.class))
                .isEqualTo("UNKNOWN_PAYMENT");
    }

    @Test
    void unsigned_body_with_nul_bytes_is_401_and_still_logged() {
        byte[] body = "{\"a\":\"\u0000\u0000\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        Api.Response response = api.postRaw("/webhooks/payments/mockpay", body, Map.of(
                "X-MockPay-Timestamp", String.valueOf(clock.instant().getEpochSecond()),
                "X-MockPay-Signature", "00"));

        assertThat(response.status()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT result FROM payment_webhook_logs", String.class))
                .isEqualTo("INVALID_SIGNATURE");
    }

    @Test
    void oversized_body_is_rejected_before_processing() {
        byte[] huge = new byte[64 * 1024 + 1];
        java.util.Arrays.fill(huge, (byte) ' ');

        Api.Response response = testData.sendSignedWebhook(huge, "test-webhook-secret", "evt_big");

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_webhook_logs", Integer.class)).isZero();
    }

    private void assertExactlyOnePayment(Fixture f) {
        assertThat(orderStatus(f)).isIn(PAID_OR_FULFILLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE status = 'SUCCEEDED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'OrderPaid'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT result FROM payment_webhook_logs ORDER BY id", String.class))
                .hasSize(3).containsOnlyOnce("PAID").filteredOn("DUPLICATE"::equals).hasSize(2);
    }

    private Fixture fixture() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        List<UUID> seats = testData.seats(event, vip, 2);
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer,
                CreateOrderRequest.of(event.id(), Item.seats(vip, seats), Item.ga(ga, 2))).order().getId();
        return new Fixture(orderId, buyer, testData.initiatePayment(buyer, orderId));
    }

    private String orderStatus(Fixture f) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, f.orderId);
    }

    private String paymentStatus(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    record Fixture(UUID orderId, UUID buyer, Payment payment) {}
}
