package com.ticket2h.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ticket2h.common.paging.KeysetCursor;
import com.ticket2h.common.time.Times;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Bảng {@code audit_logs} (partition theo tháng, chỉ ghi thêm). */
@Repository
class AuditLogStore {

    private static final TypeReference<Map<String, Object>> DETAILS = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    AuditLogStore(NamedParameterJdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    void insert(AuditActor actor, AuditEntry entry, Instant now) {
        jdbc.update("""
                INSERT INTO audit_logs (id, occurred_at, actor_type, actor_id, actor_role, actor_name, organizer_id,
                                        event_id, action, target_type, target_id, details)
                VALUES (:id, :at, :actorType, :actorId, :actorRole, :actorName, :organizerId, :eventId, :action,
                        :targetType, :targetId, CAST(:details AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("at", Times.utc(now))
                .addValue("actorType", actor.type().name())
                .addValue("actorId", actor.id())
                .addValue("actorRole", actor.role())
                .addValue("actorName", actor.name())
                // Ban tổ chức liên quan: ghi rõ trong entry, không thì lấy của người làm (tài khoản ORGANIZER)
                .addValue("organizerId", entry.organizerId() != null ? entry.organizerId() : actor.organizerId())
                .addValue("eventId", entry.eventId())
                .addValue("action", entry.action().name())
                .addValue("targetType", entry.action().target().name())
                .addValue("targetId", entry.targetId())
                .addValue("details", json.writeValueAsString(entry.details())));
    }

    /**
     * Một trang, mới nhất trước, keyset {@code (occurred_at, id) < (:at, :id)} (ADR-018). Bộ lọc {@code null} thì bỏ
     * qua. Không có điều kiện thời gian nên PostgreSQL ghép index của các bảng con theo thứ tự (Merge Append) và dừng
     * khi đủ {@code limit} dòng.
     */
    List<AuditRow> page(AuditAction action, UUID organizerId, UUID eventId, KeysetCursor after, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit);
        StringBuilder where = new StringBuilder("true");
        if (action != null) {
            where.append(" AND action = :action");
            params.addValue("action", action.name());
        }
        if (organizerId != null) {
            where.append(" AND organizer_id = :organizerId");
            params.addValue("organizerId", organizerId);
        }
        if (eventId != null) {
            where.append(" AND event_id = :eventId");
            params.addValue("eventId", eventId);
        }
        if (after != null) {
            where.append(" AND (occurred_at, id) < (:afterAt, :afterId)");
            params.addValue("afterAt", Times.utc(after.at())).addValue("afterId", after.id());
        }
        return jdbc.query("""
                SELECT id, occurred_at, actor_type, actor_id, actor_role, actor_name, organizer_id, event_id, action,
                       target_type, target_id, details::text AS details
                FROM audit_logs
                WHERE %s
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit
                """.formatted(where), params, this::row);
    }

    private AuditRow row(ResultSet rs, int i) throws SQLException {
        return new AuditRow(rs.getObject("id", UUID.class),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                AuditActor.Type.valueOf(rs.getString("actor_type")), rs.getObject("actor_id", UUID.class),
                rs.getString("actor_role"), rs.getString("actor_name"), rs.getObject("organizer_id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getString("action"), rs.getString("target_type"),
                rs.getObject("target_id", UUID.class), json.readValue(rs.getString("details"), DETAILS));
    }

    /** {@code action}, {@code targetType} để dạng chuỗi: dòng cũ của thao tác đã bỏ khỏi enum vẫn đọc được. */
    record AuditRow(UUID id, Instant occurredAt, AuditActor.Type actorType, UUID actorId, String actorRole,
            String actorName, UUID organizerId, UUID eventId, String action, String targetType, UUID targetId,
            Map<String, Object> details) {}
}
