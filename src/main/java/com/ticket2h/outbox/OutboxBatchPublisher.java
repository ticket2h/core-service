package com.ticket2h.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.events.Topics;
import com.ticket2h.common.time.Times;

/**
 * Đẩy một lô outbox lên Kafka. Gửi được thì đánh dấu {@code published_at}. Sập giữa lúc gửi xong và
 * lúc đánh dấu thì lần sau gửi lại: outbox đảm bảo "ít nhất một lần", consumer phải xử lý trùng an toàn.
 *
 * <p>Đánh đổi (ADR-007): giữ transaction trong lúc chờ Kafka xác nhận. Nhờ {@code SKIP LOCKED},
 * nhiều instance chạy relay cùng lúc không gửi trùng một lô.
 */
@Component
public class OutboxBatchPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxBatchPublisher.class);
    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;

    OutboxBatchPublisher(NamedParameterJdbcTemplate jdbc, KafkaTemplate<String, String> kafka, Clock clock) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.clock = clock;
    }

    /** @return số event đã gửi. Ít hơn {@code limit} nghĩa là đã hết việc hoặc gặp lỗi. */
    @Transactional
    public int publishBatch(int limit) {
        List<OutboxRow> rows = jdbc.query("""
                SELECT id, aggregate_id, event_type, topic, payload::text AS payload
                FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, Map.of("limit", limit), (rs, i) -> new OutboxRow(
                        rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                        rs.getString("event_type"), rs.getString("topic"), rs.getString("payload")));

        int published = 0;
        for (OutboxRow row : rows) {
            try {
                kafka.send(toRecord(row)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markFailed(row, e);
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                log.warn("Không gửi được outbox event {} ({}), thử lại lần sau: {}", row.id(), row.eventType(),
                        e.toString());
                markFailed(row, e);
                break;   // giữ thứ tự: event sau không vượt event trước đang lỗi
            }
            jdbc.update("UPDATE outbox_events SET published_at = :now WHERE id = :id",
                    Map.of("now", Times.utc(clock.instant()), "id", row.id()));
            published++;
        }
        return published;
    }

    private void markFailed(OutboxRow row, Exception e) {
        jdbc.update("UPDATE outbox_events SET attempts = attempts + 1, last_error = :error WHERE id = :id",
                Map.of("error", String.valueOf(e), "id", row.id()));
    }

    /** Key = aggregate_id: các event của cùng một đơn vào cùng partition, giữ thứ tự. */
    private static ProducerRecord<String, String> toRecord(OutboxRow row) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(row.topic(), row.aggregateId().toString(), row.payload());
        record.headers().add(Topics.HEADER_EVENT_ID, row.id().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(Topics.HEADER_EVENT_TYPE, row.eventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }

    record OutboxRow(UUID id, UUID aggregateId, String eventType, String topic, String payload) {}
}
