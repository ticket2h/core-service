package com.ticket2h.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ticket2h.common.security.InternalApiKeyFilter;
import com.ticket2h.common.security.Role;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

import tools.jackson.databind.JsonNode;

/**
 * API nội bộ cho notification-service (infra/docs/contracts.md). Khoá lấy từ application-test.yml. API tài khoản
 * ({@code /internal/users}) đã chuyển sang identity-service.
 */
class InternalApiIT extends IntegrationTest {

    private static final String KEY = "test-internal-key-notification-0123456789";
    private static final Map<String, String> SERVICE = Map.of(InternalApiKeyFilter.HEADER, KEY);

    @Test
    void missing_or_wrong_key_is_401() {
        String path = "/internal/tickets?ids=" + UUID.randomUUID();

        Api.Response missing = api.get(path, null);
        Api.Response wrong = api.get(path, null, Map.of(InternalApiKeyFilter.HEADER, KEY + "x"));

        assertThat(missing.status()).isEqualTo(401);
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(wrong.code()).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void a_user_jwt_does_not_open_the_internal_api_even_for_admin() {
        UUID admin = testData.user(Role.ADMIN);

        assertThat(api.get("/internal/tickets?ids=" + UUID.randomUUID(), testData.token(admin)).status())
                .isEqualTo(401);
    }

    @Test
    void the_service_key_does_not_open_user_endpoints() {
        assertThat(api.get("/me/tickets", null, SERVICE).status()).isEqualTo(401);
    }

    @Test
    void valid_tickets_carry_a_qr_token_that_checkin_accepts_and_used_tickets_carry_none() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        List<UUID> tickets = testData.issuedTickets(buyer, CreateOrderRequest.ga(event.id(), ga, 2));
        String ids = tickets.stream().map(UUID::toString).collect(Collectors.joining(","));

        Api.Response before = api.get("/internal/tickets?ids=" + ids, null, SERVICE);

        assertThat(before.status()).isEqualTo(200);
        assertThat(before.header("Cache-Control")).contains("no-store");
        Map<String, JsonNode> byId = byTicketId(before);
        assertThat(byId).hasSize(2);
        JsonNode first = byId.get(tickets.getFirst().toString());
        assertThat(first.get("ownerUserId").asString()).isEqualTo(buyer.toString());
        assertThat(first.get("status").asString()).isEqualTo("VALID");
        assertThat(first.get("tokenVersion").asInt()).isEqualTo(1);
        assertThat(first.get("eventTimeZone").asString()).isNotBlank();

        String staff = testData.token(testData.assignedStaff(event));
        assertThat(checkin(staff, first.get("qrToken").asString())).isEqualTo("OK");

        JsonNode afterCheckin = byTicketId(api.get("/internal/tickets?ids=" + ids, null, SERVICE))
                .get(tickets.getFirst().toString());
        assertThat(afterCheckin.get("status").asString()).isEqualTo("USED");
        assertThat(afterCheckin.hasNonNull("qrToken")).isFalse();
    }

    @Test
    void unknown_ids_are_left_out_and_more_than_fifty_ids_is_400() {
        Api.Response unknown = api.get("/internal/tickets?ids=" + UUID.randomUUID(), null, SERVICE);
        String tooMany = Stream.generate(UUID::randomUUID).limit(InternalTicketController.MAX_IDS + 1)
                .map(UUID::toString).collect(Collectors.joining(","));

        assertThat(unknown.status()).isEqualTo(200);
        assertThat(unknown.json().size()).isZero();
        assertThat(api.get("/internal/tickets?ids=" + tooMany, null, SERVICE).status()).isEqualTo(400);
    }

    private String checkin(String staffToken, String qr) {
        return api.post("/checkin", staffToken, Map.of("qrToken", qr, "gate", "A1")).json().get("result").asString();
    }

    private static Map<String, JsonNode> byTicketId(Api.Response response) {
        return response.json().valueStream().collect(Collectors.toMap(t -> t.get("ticketId").asString(), t -> t));
    }
}
