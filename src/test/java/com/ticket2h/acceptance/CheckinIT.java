package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.common.security.Role;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Api;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;
import com.ticket2h.support.TestKeys;
import com.ticket2h.ticketing.QrTokenCodec;
import com.ticket2h.ticketing.TicketClaims;

/** Bước 18: soát vé. */
class CheckinIT extends IntegrationTest {

    @Test
    void valid_qr_is_ok_and_ticket_becomes_used() {
        Fixture f = fixture(1);

        Api.Response response = checkin(f.staffToken, testData.qrToken(f.tickets.getFirst()), "A1");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("result").asString()).isEqualTo("OK");
        assertThat(response.json().get("ticket").get("ticketType").asString()).startsWith("GA");
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, f.tickets.getFirst()))
                .isEqualTo("USED");
    }

    @Test
    void rescanning_reports_when_and_where_it_was_first_used() {
        Fixture f = fixture(1);
        String qr = testData.qrToken(f.tickets.getFirst());
        checkin(f.staffToken, qr, "A1");

        Api.Response again = checkin(f.staffToken, qr, "B2");

        assertThat(again.json().get("result").asString()).isEqualTo("ALREADY_USED");
        assertThat(again.json().get("checkedInGate").asString()).isEqualTo("A1");
        assertThat(again.json().get("checkedInAt").asString()).isEqualTo(clock.instant().toString());
        // 12:00 UTC = 19:00 giờ Việt Nam: giờ hiển thị theo múi giờ của sự kiện
        assertThat(again.json().get("message").asString()).contains("19:00:00").contains("A1");
    }

    @Test
    void same_qr_scanned_at_two_gates_at_once_succeeds_exactly_once() throws Exception {
        Fixture f = fixture(50);
        for (UUID ticket : f.tickets) {
            String qr = testData.qrToken(ticket);

            List<String> results = Concurrency.run(2, 2,
                    i -> checkin(f.staffToken, qr, i == 0 ? "A1" : "B2").json().get("result").asString());

            assertThat(results).containsExactlyInAnyOrder("OK", "ALREADY_USED");
        }
    }

    @Test
    void tampered_qr_is_invalid_token() {
        Fixture f = fixture(1);
        String qr = testData.qrToken(f.tickets.getFirst());
        int middle = qr.length() / 2;
        String tampered = qr.substring(0, middle) + (qr.charAt(middle) == 'A' ? 'B' : 'A') + qr.substring(middle + 1);

        assertThat(checkin(f.staffToken, tampered, "A1").json().get("result").asString()).isEqualTo("INVALID_TOKEN");
        assertThat(checkin(f.staffToken, "hello", "A1").json().get("result").asString()).isEqualTo("INVALID_TOKEN");
    }

    @Test
    void qr_signed_with_an_unknown_key_is_invalid_token() {
        Fixture f = fixture(1);
        KeyPair attacker = TestKeys.generate();
        QrTokenCodec forger = new QrTokenCodec("k1", attacker.getPrivate(), Map.of("k1", attacker.getPublic()));
        String forged = forger.encode(new TicketClaims(f.tickets.getFirst(), f.event.id(), 1));

        assertThat(checkin(f.staffToken, forged, "A1").json().get("result").asString()).isEqualTo("INVALID_TOKEN");
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, f.tickets.getFirst()))
                .isEqualTo("VALID");
    }

    @Test
    void staff_not_assigned_to_the_event_gets_403() {
        Fixture f = fixture(1);
        String otherStaff = testData.token(testData.user(Role.STAFF));

        Api.Response response = checkin(otherStaff, testData.qrToken(f.tickets.getFirst()), "A1");

        assertThat(response.status()).isEqualTo(403);
        assertThat(response.code()).isEqualTo("FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT result FROM checkin_logs", String.class)).isEqualTo("WRONG_EVENT_STAFF");
    }

    @Test
    void customer_calling_checkin_gets_403() {
        Fixture f = fixture(1);

        Api.Response response = checkin(testData.token(f.buyer), testData.qrToken(f.tickets.getFirst()), "A1");

        assertThat(response.status()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT status FROM tickets WHERE id = ?", String.class, f.tickets.getFirst()))
                .isEqualTo("VALID");
    }

    @Test
    void every_scan_is_logged_including_failures() {
        Fixture f = fixture(1);
        String qr = testData.qrToken(f.tickets.getFirst());

        checkin(f.staffToken, qr, "A1");
        checkin(f.staffToken, qr, "A1");
        checkin(f.staffToken, "garbage", "A2");

        assertThat(jdbc.queryForList("SELECT result FROM checkin_logs ORDER BY id", String.class))
                .containsExactly("OK", "ALREADY_USED", "INVALID_TOKEN");
    }

    private Api.Response checkin(String token, String qr, String gate) {
        return api.post("/checkin", token, Map.of("qrToken", qr, "gate", gate));
    }

    private Fixture fixture(int tickets) {
        EventRef event = testData.eventOnSale(e -> e.maxTicketsPerUser(Math.max(4, tickets)));
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 100);
        UUID buyer = testData.user();
        List<UUID> issued = testData.issuedTickets(buyer, CreateOrderRequest.ga(event.id(), ga, tickets));
        UUID staff = testData.assignedStaff(event);
        return new Fixture(event, buyer, issued, testData.token(staff));
    }

    record Fixture(EventRef event, UUID buyer, List<UUID> tickets, String staffToken) {}
}
