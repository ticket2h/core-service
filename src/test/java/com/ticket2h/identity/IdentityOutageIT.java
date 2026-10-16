package com.ticket2h.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.common.security.Role;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

/**
 * Hai chức năng của core cần tra email ở identity-service: chuyển nhượng vé và phân công nhân viên soát vé.
 * identity không trả lời thì báo 502 rõ ràng và không đổi dữ liệu; identity sống lại thì làm được như thường.
 */
class IdentityOutageIT extends IntegrationTest {

    @Test
    void transfer_while_identity_is_down_is_502_and_changes_nothing() {
        Fixture f = fixture();
        identity.failNext(1);

        Api.Response response = transfer(f.buyer, f.ticket, testData.emailOf(f.friend));

        assertThat(response.status()).isEqualTo(502);
        assertThat(response.code()).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT owner_user_id FROM tickets WHERE id = ?", UUID.class, f.ticket))
                .isEqualTo(f.buyer);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ticket_transfers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_type = 'TicketTransferred'",
                Integer.class)).isZero();

        assertThat(transfer(f.buyer, f.ticket, testData.emailOf(f.friend)).status()).isEqualTo(200);
    }

    /** ADR-009: người không giữ vé nhận 404 TRƯỚC khi core hỏi identity, nên không dò được email nào tồn tại. */
    @Test
    void non_owner_gets_404_without_any_email_lookup() {
        Fixture f = fixture();

        Api.Response response = transfer(f.friend, f.ticket, testData.emailOf(f.buyer));

        assertThat(response.status()).isEqualTo(404);
        assertThat(identity.lookups()).isZero();
    }

    @Test
    void assigning_staff_while_identity_is_down_is_502() {
        EventRef event = testData.eventOnSale(e -> {});
        testData.userWithEmail("gate@test.local", Role.STAFF, null);
        String organizer = testData.token(event.organizer().userId());
        identity.failNext(1);

        Api.Response response = api.post("/organizer/events/" + event.id() + "/staff", organizer,
                Map.of("email", "gate@test.local"));

        assertThat(response.status()).isEqualTo(502);
        assertThat(response.code()).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_staff", Integer.class)).isZero();
    }

    private Api.Response transfer(UUID from, UUID ticket, String email) {
        return api.post("/tickets/" + ticket + "/transfer", testData.token(from), Map.of("recipientEmail", email));
    }

    private Fixture fixture() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        List<UUID> tickets = testData.issuedTickets(buyer, CreateOrderRequest.ga(event.id(), ga, 1));
        return new Fixture(buyer, testData.user(), tickets.getFirst());
    }

    record Fixture(UUID buyer, UUID friend, UUID ticket) {}
}
