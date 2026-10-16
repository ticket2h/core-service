package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Api;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

/** Bước 19: chuyển nhượng vé. */
class TicketTransferIT extends IntegrationTest {

    @Test
    void recipient_sees_the_ticket_and_sender_no_longer_does() {
        Fixture f = fixture(1);
        UUID ticket = f.tickets.getFirst();

        Api.Response response = transfer(f.buyer, ticket, f.friendEmail);

        assertThat(response.status()).isEqualTo(200);
        assertThat(ids(api.get("/me/tickets", testData.token(f.friend)))).containsExactly(ticket.toString());
        assertThat(ids(api.get("/me/tickets", testData.token(f.buyer)))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_transfers", Integer.class)).isEqualTo(1);
    }

    @Test
    void old_qr_is_revoked_and_new_owner_qr_works() {
        Fixture f = fixture(1);
        UUID ticket = f.tickets.getFirst();
        String oldQr = testData.qrToken(ticket);
        transfer(f.buyer, ticket, f.friendEmail);

        assertThat(checkin(f.staffToken, oldQr)).isEqualTo("TOKEN_REVOKED");
        assertThat(checkin(f.staffToken, testData.qrToken(ticket))).isEqualTo("OK");
    }

    @Test
    void someone_who_does_not_own_the_ticket_gets_404() {
        Fixture f = fixture(1);

        Api.Response response = transfer(f.friend, f.tickets.getFirst(), testData.emailOf(f.buyer));

        assertThat(response.status()).isEqualTo(404);
    }

    @Test
    void used_ticket_cannot_be_transferred() {
        Fixture f = fixture(1);
        checkin(f.staffToken, testData.qrToken(f.tickets.getFirst()));

        Api.Response response = transfer(f.buyer, f.tickets.getFirst(), f.friendEmail);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("TICKET_NOT_TRANSFERABLE");
    }

    @Test
    void transfer_is_blocked_within_two_hours_of_the_show() {
        Fixture f = fixture(1);
        clock.set(f.event.startsAt().minus(Duration.ofHours(2)).plusSeconds(1));

        Api.Response response = transfer(f.buyer, f.tickets.getFirst(), f.friendEmail);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("TICKET_NOT_TRANSFERABLE");
    }

    @Test
    void unknown_recipient_and_self_transfer_are_rejected() {
        Fixture f = fixture(1);

        assertThat(transfer(f.buyer, f.tickets.getFirst(), "nobody@test.local").code()).isEqualTo("RECIPIENT_NOT_FOUND");
        assertThat(transfer(f.buyer, f.tickets.getFirst(), testData.emailOf(f.buyer)).status()).isEqualTo(400);
    }

    /** Check-in đòi token_version khớp, chuyển nhượng đòi status VALID: luôn đúng một bên thắng. */
    @Test
    void transfer_racing_checkin_always_has_exactly_one_winner() throws Exception {
        Fixture f = fixture(50);
        for (UUID ticket : f.tickets) {
            String qr = testData.qrToken(ticket);

            List<Boolean> wins = Concurrency.run(2, 2, i -> i == 0
                    ? transfer(f.buyer, ticket, f.friendEmail).status() == 200
                    : "OK".equals(checkin(f.staffToken, qr)));

            assertThat(wins).containsExactlyInAnyOrder(true, false);
        }
    }

    @Test
    void second_transfer_bumps_version_to_three_and_only_the_latest_qr_works() {
        Fixture f = fixture(1);
        UUID ticket = f.tickets.getFirst();
        String qrA = testData.qrToken(ticket);
        transfer(f.buyer, ticket, f.friendEmail);
        String qrB = testData.qrToken(ticket);
        UUID carol = testData.user();

        transfer(f.friend, ticket, testData.emailOf(carol));

        assertThat(jdbc.queryForObject("SELECT token_version FROM tickets WHERE id = ?", Integer.class, ticket))
                .isEqualTo(3);
        assertThat(checkin(f.staffToken, qrA)).isEqualTo("TOKEN_REVOKED");
        assertThat(checkin(f.staffToken, qrB)).isEqualTo("TOKEN_REVOKED");
        assertThat(checkin(f.staffToken, testData.qrToken(ticket))).isEqualTo("OK");
    }

    @Test
    void recipient_purchase_quota_is_unchanged() {
        Fixture f = fixture(1);
        transfer(f.buyer, f.tickets.getFirst(), f.friendEmail);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_quotas WHERE user_id = ?", Integer.class, f.friend))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT reserved FROM purchase_quotas WHERE user_id = ?", Integer.class, f.buyer))
                .isEqualTo(1);
    }

    private Api.Response transfer(UUID from, UUID ticket, String email) {
        return api.post("/tickets/" + ticket + "/transfer", testData.token(from), Map.of("recipientEmail", email));
    }

    private String checkin(String staffToken, String qr) {
        return api.post("/checkin", staffToken, Map.of("qrToken", qr, "gate", "A1")).json().get("result").asString();
    }

    private static List<String> ids(Api.Response tickets) {
        return tickets.json().valueStream().map(t -> t.get("ticketId").asString()).toList();
    }

    private Fixture fixture(int tickets) {
        EventRef event = testData.eventOnSale(e -> e.maxTicketsPerUser(Math.max(4, tickets)));
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 100);
        UUID buyer = testData.user();
        List<UUID> issued = testData.issuedTickets(buyer, CreateOrderRequest.ga(event.id(), ga, tickets));
        UUID friend = testData.user();
        return new Fixture(event, buyer, friend, testData.emailOf(friend), issued,
                testData.token(testData.assignedStaff(event)));
    }

    record Fixture(EventRef event, UUID buyer, UUID friend, String friendEmail, List<UUID> tickets, String staffToken) {}
}
