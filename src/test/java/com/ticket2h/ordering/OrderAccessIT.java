package com.ticket2h.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.common.security.Role;
import com.ticket2h.payment.Payment;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;

/** Bước 12.1: phân quyền đơn hàng. */
class OrderAccessIT extends IntegrationTest {

    @Test
    void someone_elses_order_is_404_not_403() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID owner = testData.user();
        UUID orderId = testData.placeOrder(owner, CreateOrderRequest.ga(event.id(), ga, 1)).order().getId();
        String stranger = testData.token(testData.user());

        assertThat(api.get("/orders/" + orderId, stranger).status()).isEqualTo(404);
        assertThat(api.post("/orders/" + orderId + "/cancel", stranger, null).status()).isEqualTo(404);
        assertThat(api.post("/orders/" + orderId + "/payments", stranger, null).status()).isEqualTo(404);
        assertThat(api.get("/orders/" + UUID.randomUUID(), stranger).status()).isEqualTo(404);
        assertThat(api.get("/orders/" + orderId, testData.token(owner)).status()).isEqualTo(200);
    }

    @Test
    void public_seat_map_hides_holder_and_shows_expired_holds_as_available() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        List<UUID> seats = testData.seats(event, vip, 2);
        testData.placeOrder(testData.user(), CreateOrderRequest.seated(event.id(), vip, List.of(seats.get(0))));

        Api.Response held = api.get("/events/" + event.id() + "/seats", null);
        assertThat(held.status()).isEqualTo(200);
        assertThat(held.body()).doesNotContain("orderId", "order_id", "heldUntil", "held_until");
        assertThat(statusOf(held, seats.get(0))).isEqualTo("HELD");

        clock.advance(Duration.ofMinutes(11));   // quá hạn, job chưa dọn
        assertThat(statusOf(api.get("/events/" + event.id() + "/seats", null), seats.get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void webhook_does_not_trust_jwt_only_the_signature() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.ga(event.id(), ga, 1)).order().getId();
        Payment payment = testData.initiatePayment(buyer, orderId);
        byte[] body = json.writeValueAsBytes(testData.succeededWebhook(payment));

        // JWT hợp lệ nhưng không có chữ ký → 401
        Api.Response unsigned = api.postRaw("/webhooks/payments/mockpay", body,
                Map.of("Authorization", "Bearer " + testData.token(testData.user(Role.ADMIN))));
        assertThat(unsigned.status()).isEqualTo(401);
        assertThat(unsigned.code()).isEqualTo("INVALID_WEBHOOK_SIGNATURE");

        // Header Authorization rác không làm hỏng webhook có chữ ký đúng
        String ts = String.valueOf(clock.instant().getEpochSecond());
        Api.Response signed = api.postRaw("/webhooks/payments/mockpay", body, Map.of(
                "Authorization", "Bearer garbage",
                "X-MockPay-Timestamp", ts,
                "X-MockPay-Signature", com.ticket2h.support.TestData.sign("test-webhook-secret", ts, body)));
        assertThat(signed.status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isIn("PAID", "FULFILLED");
    }

    @Test
    void stale_or_garbage_token_does_not_break_public_endpoints() {
        var event = testData.eventOnSale(e -> {});
        String expired = testData.token(testData.user());
        clock.advance(java.time.Duration.ofDays(2));

        for (String token : List.of(expired, "garbage")) {
            assertThat(api.get("/events", token).status()).isEqualTo(200);
            assertThat(api.get("/events/" + event.id() + "/seats", token).status()).isEqualTo(200);
            assertThat(api.get("/ticket-keys", token).status()).isEqualTo(200);
        }
        assertThat(api.get("/me/orders", expired).status()).isEqualTo(401);
    }

    @Test
    void organizer_and_staff_cannot_place_orders() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        Map<String, Object> body = OrderApiIT.gaBody(event.id(), ga, 1);

        for (Role role : List.of(Role.ORGANIZER, Role.STAFF, Role.ADMIN)) {
            UUID user = role == Role.ORGANIZER ? testData.organizer().userId() : testData.user(role);
            Api.Response response = api.post("/orders", testData.token(user), body, Map.of("Idempotency-Key", "k"));
            assertThat(response.status()).as(role.name()).isEqualTo(403);
            assertThat(response.code()).isEqualTo("FORBIDDEN");
        }
    }

    private static String statusOf(Api.Response seatMap, UUID seatId) {
        for (var seat : seatMap.json()) {
            if (seat.get("seatId").asString().equals(seatId.toString())) {
                return seat.get("status").asString();
            }
        }
        throw new AssertionError("Không thấy ghế " + seatId);
    }
}
