package com.ticket2h.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.KafkaTestMessages;

/** Bước 14: transactional outbox. */
class OutboxRelayIT extends IntegrationTest {

    private static final String TOPIC = "ticket2h.test-events";

    @Autowired OutboxWriter outbox;
    @Autowired OutboxBatchPublisher publisher;
    @Autowired TransactionTemplate tx;
    @Autowired KafkaConnectionDetails kafka;

    @Test
    void outbox_written_in_a_rolled_back_transaction_disappears() {
        tx.executeWithoutResult(status -> {
            outbox.write("Order", UUID.randomUUID(), "Test", TOPIC, Map.of("x", 1));
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void committed_outbox_is_published_to_kafka_with_key_and_headers() {
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = tx.execute(s -> outbox.write("Order", aggregateId, "Test", TOPIC, Map.of("hello", "xin chào")));

        publisher.publishBatch(100);   // relay nền có thể đã gửi trước; kết quả cuối giống nhau

        List<ConsumerRecord<String, String>> records = consume(TOPIC, 1);
        ConsumerRecord<String, String> record = records.getFirst();
        assertThat(record.key()).isEqualTo(aggregateId.toString());
        assertThat(record.value()).contains("xin chào");
        assertThat(new String(record.headers().lastHeader("eventId").value(), StandardCharsets.UTF_8))
                .isEqualTo(eventId.toString());
        assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("Test");
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?",
                Boolean.class, eventId)).isTrue();
    }

    @Test
    void writing_outbox_outside_a_transaction_fails() {
        assertThatThrownBy(() -> outbox.write("Order", UUID.randomUUID(), "Test", TOPIC, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private List<ConsumerRecord<String, String>> consume(String topic, int expected) {
        return KafkaTestMessages.consume(kafka, topic, expected, Duration.ofSeconds(20));
    }
}
