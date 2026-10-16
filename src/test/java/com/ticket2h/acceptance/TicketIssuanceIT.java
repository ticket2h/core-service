package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import com.ticket2h.common.events.Topics;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.ordering.CreateOrderRequest.Item;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.Invariants;
import com.ticket2h.support.KafkaTestMessages;

/** Bước 15: phát vé qua outbox → Kafka → consumer. Phát vé song song từ hai luồng: CrashRecoveryIT. */
class TicketIssuanceIT extends IntegrationTest {

    @Autowired KafkaTemplate<String, String> kafka;

    @Test
    void paid_order_with_two_seats_and_two_ga_gets_four_valid_tickets() {
        Paid p = paidOrder();

        awaitTickets(p.orderId, 4);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, p.orderId)).isEqualTo("FULFILLED"));
        assertThat(jdbc.queryForList("SELECT status FROM tickets WHERE order_id = ?", String.class, p.orderId))
                .containsOnly("VALID");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT owner_user_id) FROM tickets WHERE order_id = ?",
                Integer.class, p.orderId)).isEqualTo(1);
        Invariants.assertConsistent(jdbc);
    }

    @Test
    void order_paid_delivered_two_more_times_still_gives_four_tickets() {
        Paid p = paidOrder();
        awaitTickets(p.orderId, 4);

        String payload = "{\"orderId\":\"%s\",\"userId\":\"%s\"}".formatted(p.orderId, p.buyer);
        KafkaTestMessages.send(kafka, Topics.ORDER_EVENTS, p.orderId.toString(), Topics.ORDER_PAID, payload);
        KafkaTestMessages.send(kafka, Topics.ORDER_EVENTS, p.orderId.toString(), Topics.ORDER_PAID, payload);

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(ticketCount(p.orderId)).isEqualTo(4));
    }

    @Test
    void seated_tickets_carry_their_seat_and_ga_tickets_do_not() {
        Paid p = paidOrder();
        awaitTickets(p.orderId, 4);

        assertThat(jdbc.queryForList("SELECT seat_id FROM tickets WHERE order_id = ? AND seat_id IS NOT NULL",
                UUID.class, p.orderId)).containsExactlyInAnyOrderElementsOf(p.seats);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets WHERE order_id = ? AND seat_id IS NULL",
                Integer.class, p.orderId)).isEqualTo(2);
    }

    private Paid paidOrder() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        List<UUID> seats = testData.seats(event, vip, 2);
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        UUID orderId = testData.paidOrderViaWebhook(buyer,
                CreateOrderRequest.of(event.id(), Item.seats(vip, seats), Item.ga(ga, 2)));
        return new Paid(orderId, buyer, seats);
    }

    private void awaitTickets(UUID orderId, int expected) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(ticketCount(orderId)).isEqualTo(expected));
    }

    private int ticketCount(UUID orderId) {
        return jdbc.queryForObject("SELECT count(*) FROM tickets WHERE order_id = ?", Integer.class, orderId);
    }

    record Paid(UUID orderId, UUID buyer, List<UUID> seats) {}
}
