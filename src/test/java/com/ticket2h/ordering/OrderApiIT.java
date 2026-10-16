package com.ticket2h.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.support.Api;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;

/** Bước 6: test HTTP, ít request, kiểm tra mã lỗi. */
class OrderApiIT extends IntegrationTest {

    @Test
    void missing_idempotency_key_is_400() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        String token = testData.token(testData.user());

        Api.Response response = api.post("/orders", token, gaBody(event.id(), ga, 1));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_MISSING");
    }

    @Test
    void held_seat_is_409_with_unavailable_seat_ids() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        List<UUID> seats = testData.seats(event, vip, 2);
        assertThat(post(testData.token(testData.user()), seatBody(event.id(), vip, List.of(seats.get(0))), "a")
                .status()).isEqualTo(201);

        Api.Response response = post(testData.token(testData.user()), seatBody(event.id(), vip, seats), "b");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("SEAT_UNAVAILABLE");
        assertThat(response.json().get("type").asString()).isEqualTo("https://ticket2h.dev/errors/seat-unavailable");
        assertThat(response.json().get("unavailableSeatIds").get(0).asString()).isEqualTo(seats.get(0).toString());
    }

    @Test
    void replay_is_200_with_idempotent_replayed_header_and_same_body() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        String token = testData.token(testData.user());

        Api.Response first = post(token, gaBody(event.id(), ga, 2), "key-1");
        Api.Response second = post(token, gaBody(event.id(), ga, 2), "key-1");

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.header("Idempotent-Replayed")).isNull();
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(second.json()).isEqualTo(first.json());
        assertThat(first.json().get("totalAmount").decimalValue()).isEqualByComparingTo("1600000");
    }

    @Test
    void same_key_different_body_is_422() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        String token = testData.token(testData.user());
        post(token, gaBody(event.id(), ga, 1), "key-1");

        Api.Response response = post(token, gaBody(event.id(), ga, 2), "key-1");

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void twenty_parallel_http_requests_for_one_seat_give_one_201_and_nineteen_409_never_500() throws Exception {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        UUID seat = testData.seats(event, vip, 1).getFirst();
        List<String> tokens = testData.users(20).stream().map(testData::token).toList();

        List<Integer> statuses = Concurrency.run(20, 20,
                i -> post(tokens.get(i), seatBody(event.id(), vip, List.of(seat)), UUID.randomUUID().toString())
                        .status());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(19);
    }

    @Test
    void price_sent_by_client_is_ignored() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        String token = testData.token(testData.user());
        String body = """
                {"eventId":"%s","items":[{"ticketTypeId":"%s","quantity":2,"price":1,"unitPrice":1}],"totalAmount":2}
                """.formatted(event.id(), ga);

        Api.Response response = api.post("/orders", token, body, Map.of("Idempotency-Key", "k"));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("totalAmount").decimalValue()).isEqualByComparingTo("1600000");
        assertThat(response.json().get("items").get(0).get("unitPrice").decimalValue()).isEqualByComparingTo("800000");
    }

    @Test
    void invalid_bodies_are_400_validation_failed() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        UUID seat = testData.seats(event, vip, 1).getFirst();
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        String token = testData.token(testData.user());

        // vừa có seatIds vừa có quantity
        assertThat(post(token, Map.of("eventId", event.id(), "items", List.of(
                Map.of("ticketTypeId", vip, "seatIds", List.of(seat), "quantity", 1))), "a").code())
                .isEqualTo("VALIDATION_FAILED");
        // ghế trùng
        assertThat(post(token, seatBody(event.id(), vip, List.of(seat, seat)), "b").code())
                .isEqualTo("VALIDATION_FAILED");
        // quá 10 ghế
        List<UUID> elevenSeats = java.util.stream.Stream.generate(UUID::randomUUID).limit(11).toList();
        assertThat(post(token, seatBody(event.id(), vip, elevenSeats), "c").status()).isEqualTo(400);
        // GA không có quantity
        assertThat(post(token, Map.of("eventId", event.id(), "items", List.of(Map.of("ticketTypeId", ga))), "d")
                .code()).isEqualTo("VALIDATION_FAILED");
        // JSON hỏng
        assertThat(api.post("/orders", token, "{not json", Map.of("Idempotency-Key", "e")).code())
                .isEqualTo("VALIDATION_FAILED");
    }

    private Api.Response post(String token, Object body, String key) {
        return api.post("/orders", token, body, Map.of("Idempotency-Key", key));
    }

    static Map<String, Object> gaBody(UUID eventId, UUID ticketTypeId, int quantity) {
        return Map.of("eventId", eventId, "items", List.of(Map.of("ticketTypeId", ticketTypeId, "quantity", quantity)));
    }

    static Map<String, Object> seatBody(UUID eventId, UUID ticketTypeId, List<UUID> seatIds) {
        return Map.of("eventId", eventId, "items", List.of(Map.of("ticketTypeId", ticketTypeId, "seatIds", seatIds)));
    }
}
