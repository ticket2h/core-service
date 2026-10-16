package com.ticket2h.outbox;

import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.time.Times;

import tools.jackson.databind.json.JsonMapper;

/**
 * Ghi event vào {@code outbox_events} trong CÙNG transaction với thay đổi nghiệp vụ: hai việc cùng
 * commit hoặc cùng rollback. {@code MANDATORY} làm code ném lỗi nếu ai đó gọi ngoài transaction.
 */
@Component
public class OutboxWriter {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json;

    OutboxWriter(NamedParameterJdbcTemplate jdbc, Clock clock, JsonMapper json) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(String aggregateType, UUID aggregateId, String eventType, String topic, Object payload) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload, created_at)
                VALUES (:id, :aggregateType, :aggregateId, :eventType, :topic, CAST(:payload AS jsonb), :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("aggregateType", aggregateType)
                .addValue("aggregateId", aggregateId)
                .addValue("eventType", eventType)
                .addValue("topic", topic)
                .addValue("payload", json.writeValueAsString(payload))
                .addValue("createdAt", Times.utc(clock.instant())));
        return id;
    }
}
