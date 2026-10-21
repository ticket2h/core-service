package com.ticket2h.ticketing;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ticket2h.common.time.Times;

/**
 * Đọc bảng {@code checkin_logs} (bảng partition theo tháng, ADR-019). Ghi vẫn ở {@link TicketStore#insertCheckinLog}.
 */
@Repository
class CheckinLogStore {

    /** Số dòng driver PostgreSQL lấy về mỗi lần khi đọc theo luồng. */
    static final int FETCH_SIZE = 1_000;

    private final NamedParameterJdbcTemplate streaming;
    private final NamedParameterJdbcTemplate jdbc;

    CheckinLogStore(DataSource dataSource, NamedParameterJdbcTemplate jdbc) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(FETCH_SIZE);
        this.streaming = new NamedParameterJdbcTemplate(template);
        this.jdbc = jdbc;
    }

    /** Nhân viên có lượt quét trong khoảng thời gian (để hỏi tên một lần trước khi đọc theo luồng). */
    List<UUID> staffIn(UUID eventId, Instant from, Instant to) {
        return jdbc.queryForList("""
                SELECT DISTINCT staff_user_id FROM checkin_logs
                WHERE event_id = :eventId AND scanned_at >= :from AND scanned_at < :to
                """, range(eventId, from, to), UUID.class);
    }

    /**
     * Gọi {@code row} cho từng lượt quét, theo thời gian, không gom vào {@code List}. Phải chạy TRONG transaction:
     * driver PostgreSQL chỉ đọc theo cursor (mỗi lần {@value #FETCH_SIZE} dòng) khi autocommit tắt; ngoài transaction
     * nó tải hết kết quả vào bộ nhớ rồi mới trả dòng đầu tiên. Điều kiện trên {@code scanned_at} cho PostgreSQL bỏ
     * qua các bảng con ngoài khoảng (partition pruning).
     */
    void stream(UUID eventId, Instant from, Instant to, Consumer<CheckinLogRow> row) {
        streaming.query("""
                SELECT scanned_at, gate, result, ticket_id, staff_user_id FROM checkin_logs
                WHERE event_id = :eventId AND scanned_at >= :from AND scanned_at < :to
                ORDER BY scanned_at, id
                """, range(eventId, from, to), rs -> {
            row.accept(new CheckinLogRow(rs.getObject("scanned_at", OffsetDateTime.class).toInstant(),
                    rs.getString("gate"), CheckinResult.valueOf(rs.getString("result")),
                    rs.getObject("ticket_id", UUID.class), rs.getObject("staff_user_id", UUID.class)));
        });
    }

    private static MapSqlParameterSource range(UUID eventId, Instant from, Instant to) {
        return new MapSqlParameterSource().addValue("eventId", eventId).addValue("from", Times.utc(from))
                .addValue("to", Times.utc(to));
    }

    record CheckinLogRow(Instant scannedAt, String gate, CheckinResult result, UUID ticketId, UUID staffUserId) {}
}
