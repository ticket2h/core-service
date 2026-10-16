package com.ticket2h.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;

/** Bước 9. Cổng thanh toán là FakeGateway (HTTP server trong test), không cần chạy MockPay. */
class PaymentInitiationIT extends IntegrationTest {

    @Test
    void initiating_returns_payment_url_and_extends_the_hold() {
        Fixture f = fixture();
        clock.advance(Duration.ofMinutes(9));   // bấm thanh toán ở phút thứ 9

        Api.Response response = api.post("/orders/" + f.orderId + "/payments", f.token, null);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("paymentUrl").asString()).contains("/pay/mp_");
        assertThat(response.json().get("status").asString()).isEqualTo("INITIATED");
        Instant extended = clock.instant().plus(Duration.ofMinutes(15));
        assertThat(timestamp("SELECT expires_at FROM orders WHERE id = ?", f.orderId)).isEqualTo(extended);
        assertThat(timestamp("SELECT held_until FROM seats WHERE order_id = ? LIMIT 1", f.orderId)).isEqualTo(extended);
        assertThat(gateway.createRequests()).hasSize(1);
        assertThat(gateway.createRequests().getFirst().get("merchantRef").asString())
                .isEqualTo(response.json().get("paymentId").asString());
        assertThat(gateway.createRequests().getFirst().get("amount").decimalValue()).isEqualByComparingTo("1500000");
    }

    @Test
    void initiating_twice_returns_the_same_payment() {
        Fixture f = fixture();

        Api.Response first = api.post("/orders/" + f.orderId + "/payments", f.token, null);
        Api.Response second = api.post("/orders/" + f.orderId + "/payments", f.token, null);

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().get("paymentId")).isEqualTo(first.json().get("paymentId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isEqualTo(1);
    }

    @Test
    void gateway_500_gives_502_payment_failed_order_still_pending() {
        Fixture f = fixture();
        gateway.failNextCreates(1);

        Api.Response response = api.post("/orders/" + f.orderId + "/payments", f.token, null);

        assertThat(response.status()).isEqualTo(502);
        assertThat(response.code()).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM payments", String.class)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, f.orderId))
                .isEqualTo("PENDING_PAYMENT");

        // Khách thử lại được
        assertThat(api.post("/orders/" + f.orderId + "/payments", f.token, null).status()).isEqualTo(201);
    }

    @Test
    void gateway_timeout_gives_502() {
        Fixture f = fixture();
        gateway.delayCreates(3_000);   // read-timeout trong test là 2 giây

        Api.Response response = api.post("/orders/" + f.orderId + "/payments", f.token, null);

        assertThat(response.status()).isEqualTo(502);
        assertThat(jdbc.queryForObject("SELECT status FROM payments", String.class)).isEqualTo("FAILED");
    }

    @Test
    void attempts_are_capped() {
        Fixture f = fixture();
        gateway.failNextCreates(3);
        for (int i = 0; i < 3; i++) {
            assertThat(api.post("/orders/" + f.orderId + "/payments", f.token, null).status()).isEqualTo(502);
        }

        Api.Response response = api.post("/orders/" + f.orderId + "/payments", f.token, null);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("PAYMENT_ATTEMPTS_EXCEEDED");
    }

    @Test
    void expired_order_is_409_not_payable() {
        Fixture f = fixture();
        clock.advance(Duration.ofMinutes(10));

        Api.Response response = api.post("/orders/" + f.orderId + "/payments", f.token, null);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("ORDER_NOT_PAYABLE");
        assertThat(gateway.createRequests()).isEmpty();
    }

    @Test
    void someone_elses_order_is_404() {
        Fixture f = fixture();

        Api.Response response = api.post("/orders/" + f.orderId + "/payments", testData.token(testData.user()), null);

        assertThat(response.status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
    }

    private Fixture fixture() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        List<UUID> seats = testData.seats(event, vip, 1);
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.seated(event.id(), vip, seats)).order().getId();
        return new Fixture(orderId, testData.token(buyer));
    }

    private Instant timestamp(String sql, UUID id) {
        return jdbc.queryForObject(sql, OffsetDateTime.class, id).toInstant();
    }

    record Fixture(UUID orderId, String token) {}
}
