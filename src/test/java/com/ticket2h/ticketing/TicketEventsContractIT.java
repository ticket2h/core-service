package com.ticket2h.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;

import com.ticket2h.common.events.Topics;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.KafkaTestMessages;
import com.ticket2h.support.TestData.EventRef;

import tools.jackson.databind.JsonNode;

/**
 * Hợp đồng với notification-service (infra/docs/contracts.md): topic, key, header và tên trường của message
 * trên {@code ticket2h.ticket-events}. Test này đỏ nghĩa là notification-service sẽ không đọc được message.
 */
class TicketEventsContractIT extends IntegrationTest {

    @Autowired KafkaConnectionDetails kafkaConnection;

    @Test
    void tickets_issued_message_has_the_documented_shape() {
        Fixture f = fixture();
        UUID orderId = jdbc.queryForObject("SELECT order_id FROM tickets WHERE id = ?", UUID.class, f.tickets.getFirst());

        ConsumerRecord<String, String> record = find(Topics.TICKETS_ISSUED, orderId);

        assertThat(record.key()).isEqualTo(orderId.toString());
        assertThat(header(record, "eventId")).isNotBlank();
        JsonNode payload = json.readTree(record.value());
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder("orderId", "userId", "ticketIds");
        assertThat(payload.get("userId").asString()).isEqualTo(f.buyer.toString());
        assertThat(payload.get("ticketIds").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrderElementsOf(f.tickets.stream().map(UUID::toString).toList());
    }

    @Test
    void ticket_transferred_message_has_the_documented_shape() {
        Fixture f = fixture();
        UUID ticket = f.tickets.getFirst();
        UUID friend = testData.user();
        api.post("/tickets/" + ticket + "/transfer", testData.token(f.buyer),
                Map.of("recipientEmail", testData.emailOf(friend)));

        ConsumerRecord<String, String> record = find(Topics.TICKET_TRANSFERRED, ticket);

        assertThat(record.key()).isEqualTo(ticket.toString());
        JsonNode payload = json.readTree(record.value());
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
                "ticketId", "eventId", "fromUserId", "toUserId", "tokenVersion", "transferredAt");
        assertThat(payload.get("eventId").asString()).isEqualTo(f.event.id().toString());
        assertThat(payload.get("fromUserId").asString()).isEqualTo(f.buyer.toString());
        assertThat(payload.get("toUserId").asString()).isEqualTo(friend.toString());
        assertThat(payload.get("tokenVersion").asInt()).isEqualTo(2);
        assertThat(Instant.parse(payload.get("transferredAt").asString())).isNotNull();
    }

    /** Message của đúng aggregate này (topic không bị dọn giữa các test). */
    private ConsumerRecord<String, String> find(String eventType, UUID aggregateId) {
        return KafkaTestMessages.consumeFirst(kafkaConnection, Topics.TICKET_EVENTS,
                r -> eventType.equals(header(r, Topics.HEADER_EVENT_TYPE)) && aggregateId.toString().equals(r.key()),
                Duration.ofSeconds(15));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private Fixture fixture() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        return new Fixture(event, buyer, testData.issuedTickets(buyer, CreateOrderRequest.ga(event.id(), ga, 2)));
    }

    record Fixture(EventRef event, UUID buyer, List<UUID> tickets) {}
}
