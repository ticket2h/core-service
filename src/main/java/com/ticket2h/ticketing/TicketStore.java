package com.ticket2h.ticketing;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import com.ticket2h.common.time.Times;

/**
 * SQL của bảng {@code tickets}. Check-in và chuyển nhượng đổi trạng thái bằng câu lệnh có điều kiện,
 * không load vé thành entity.
 */
@Repository
class TicketStore {

    /** Dòng vé kèm thông tin hiển thị (sự kiện, loại vé, ghế). Đọc chéo bảng của catalog, chỉ để hiển thị. */
    private static final String VIEW_SELECT = """
            SELECT t.id, t.order_id, t.event_id, t.ticket_type_id, t.seat_id, t.owner_user_id, t.status,
                   t.token_version, t.issued_at, t.checked_in_at, t.checked_in_gate,
                   e.title AS event_title, e.starts_at AS event_starts_at, e.time_zone AS event_time_zone,
                   tt.name AS ticket_type_name, s.section, s.row_label, s.seat_number
            FROM tickets t
            JOIN events e ON e.id = t.event_id
            JOIN ticket_types tt ON tt.id = t.ticket_type_id
            LEFT JOIN seats s ON s.id = t.seat_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    TicketStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Phát vé. {@code ON CONFLICT DO NOTHING}: phát lặp lại (message trùng) không tạo thêm vé. */
    void insertTickets(List<NewTicket> tickets, Instant now) {
        SqlParameterSource[] batch = tickets.stream()
                .map(t -> new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID()).addValue("orderId", t.orderId())
                        .addValue("orderItemId", t.orderItemId()).addValue("itemSeq", t.itemSeq())
                        .addValue("eventId", t.eventId()).addValue("ticketTypeId", t.ticketTypeId())
                        .addValue("seatId", t.seatId()).addValue("ownerUserId", t.ownerUserId())
                        .addValue("issuedAt", Times.utc(now)))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO tickets (id, order_id, order_item_id, item_seq, event_id, ticket_type_id, seat_id,
                                     owner_user_id, status, token_version, issued_at)
                VALUES (:id, :orderId, :orderItemId, :itemSeq, :eventId, :ticketTypeId, :seatId,
                        :ownerUserId, 'VALID', 1, :issuedAt)
                ON CONFLICT (order_item_id, item_seq) DO NOTHING
                """, batch);
    }

    List<UUID> ticketIdsOfOrder(UUID orderId) {
        return jdbc.queryForList("SELECT id FROM tickets WHERE order_id = :orderId ORDER BY order_item_id, item_seq",
                Map.of("orderId", orderId), UUID.class);
    }

    List<TicketView> viewsOwnedBy(UUID ownerUserId) {
        return jdbc.query(VIEW_SELECT + " WHERE t.owner_user_id = :owner ORDER BY e.starts_at, t.issued_at, t.id",
                Map.of("owner", ownerUserId), TicketStore::view);
    }

    Optional<TicketView> view(UUID ticketId) {
        return jdbc.query(VIEW_SELECT + " WHERE t.id = :id", Map.of("id", ticketId), TicketStore::view)
                .stream().findFirst();
    }

    List<TicketView> views(List<UUID> ticketIds) {
        if (ticketIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(VIEW_SELECT + " WHERE t.id IN (:ids) ORDER BY t.order_item_id, t.item_seq",
                Map.of("ids", ticketIds), TicketStore::view);
    }

    /** Check-in: chỉ thành công khi vé VALID và token_version khớp QR. */
    int markUsed(UUID ticketId, UUID eventId, int tokenVersion, UUID staffId, String gate, Instant now) {
        return jdbc.update("""
                UPDATE tickets
                SET status = 'USED', checked_in_at = :now, checked_in_gate = :gate, checked_in_by = :staffId
                WHERE id = :ticketId AND event_id = :eventId
                  AND status = 'VALID' AND token_version = :tokenVersion
                """, new MapSqlParameterSource()
                .addValue("now", Times.utc(now)).addValue("gate", gate).addValue("staffId", staffId)
                .addValue("ticketId", ticketId).addValue("eventId", eventId).addValue("tokenVersion", tokenVersion));
    }

    /** Chuyển nhượng: đổi chủ và tăng token_version (QR cũ mất hiệu lực). Trả version mới, rỗng nếu 0 dòng. */
    Optional<Integer> transfer(UUID ticketId, UUID fromUserId, UUID toUserId) {
        return jdbc.queryForList("""
                UPDATE tickets
                SET owner_user_id = :toUserId, token_version = token_version + 1
                WHERE id = :ticketId AND owner_user_id = :fromUserId AND status = 'VALID'
                RETURNING token_version
                """, Map.of("toUserId", toUserId, "ticketId", ticketId, "fromUserId", fromUserId), Integer.class)
                .stream().findFirst();
    }

    void insertTransfer(UUID ticketId, UUID fromUserId, UUID toUserId, int newTokenVersion, Instant now) {
        jdbc.update("""
                INSERT INTO ticket_transfers (id, ticket_id, from_user_id, to_user_id, new_token_version, transferred_at)
                VALUES (:id, :ticketId, :fromUserId, :toUserId, :version, :now)
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID()).addValue("ticketId", ticketId).addValue("fromUserId", fromUserId)
                .addValue("toUserId", toUserId).addValue("version", newTokenVersion).addValue("now", Times.utc(now)));
    }

    /**
     * Một lô vé của sự kiện cho file danh sách khách, theo thứ tự phát vé, đi tiếp từ vé cuối của lô trước (keyset:
     * {@code (issued_at, id) > (...)}, index {@code idx_tickets_event_issued}). Mỗi lô là một câu SELECT tự commit:
     * giữa hai lô core còn gọi identity, không được giữ transaction trong lúc gọi mạng. Bỏ vé VOID.
     */
    List<AttendeeRow> attendeeBatch(UUID eventId, AttendeeRow after, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("eventId", eventId)
                .addValue("limit", limit);
        String afterCondition = "";
        if (after != null) {
            afterCondition = "AND (t.issued_at, t.id) > (:afterIssuedAt, :afterId)";
            params.addValue("afterIssuedAt", Times.utc(after.issuedAt())).addValue("afterId", after.ticketId());
        }
        return jdbc.query("""
                SELECT t.id, t.issued_at, t.status, t.owner_user_id, t.checked_in_at, t.checked_in_gate,
                       tt.name AS ticket_type_name, s.section, s.row_label, s.seat_number
                FROM tickets t
                JOIN ticket_types tt ON tt.id = t.ticket_type_id
                LEFT JOIN seats s ON s.id = t.seat_id
                WHERE t.event_id = :eventId AND t.status <> 'VOID' %s
                ORDER BY t.issued_at, t.id
                LIMIT :limit
                """.formatted(afterCondition), params, (rs, i) -> new AttendeeRow(
                        rs.getObject("id", UUID.class), rs.getObject("issued_at", OffsetDateTime.class).toInstant(),
                        TicketStatus.valueOf(rs.getString("status")), rs.getObject("owner_user_id", UUID.class),
                        rs.getString("ticket_type_name"), seatLabel(rs),
                        toInstant(rs.getObject("checked_in_at", OffsetDateTime.class)), rs.getString("checked_in_gate")));
    }

    /** Ghi mọi lượt quét, kể cả thất bại. ticket_id/event_id rỗng khi QR không giải mã được. */
    void insertCheckinLog(UUID ticketId, UUID eventId, UUID staffId, String gate, CheckinResult result, Instant now) {
        jdbc.update("""
                INSERT INTO checkin_logs (ticket_id, event_id, staff_user_id, gate, result, scanned_at)
                VALUES (:ticketId, :eventId, :staffId, :gate, :result, :now)
                """, new MapSqlParameterSource()
                .addValue("ticketId", ticketId).addValue("eventId", eventId).addValue("staffId", staffId)
                .addValue("gate", gate).addValue("result", result.name()).addValue("now", Times.utc(now)));
    }

    /** "VIP A-12"; vé GA (không có ghế) là null. */
    private static String seatLabel(ResultSet rs) throws SQLException {
        String section = rs.getString("section");
        return section == null ? null : section + " " + rs.getString("row_label") + "-" + rs.getInt("seat_number");
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static TicketView view(ResultSet rs, int i) throws SQLException {
        String seatLabel = seatLabel(rs);
        return new TicketView(
                rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getObject("event_id", UUID.class), rs.getObject("ticket_type_id", UUID.class),
                rs.getObject("seat_id", UUID.class), rs.getObject("owner_user_id", UUID.class),
                TicketStatus.valueOf(rs.getString("status")), rs.getInt("token_version"),
                instant(rs, "issued_at"), instant(rs, "checked_in_at"), rs.getString("checked_in_gate"),
                rs.getString("event_title"), instant(rs, "event_starts_at"),
                ZoneId.of(rs.getString("event_time_zone")), rs.getString("ticket_type_name"), seatLabel);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record NewTicket(UUID orderId, UUID orderItemId, int itemSeq, UUID eventId, UUID ticketTypeId, UUID seatId,
            UUID ownerUserId) {}

    /** Một dòng của file danh sách khách. {@code seat} null với vé GA; {@code checkedInAt} null khi chưa vào cổng. */
    record AttendeeRow(UUID ticketId, Instant issuedAt, TicketStatus status, UUID ownerUserId, String ticketType,
            String seat, Instant checkedInAt, String gate) {}
}
