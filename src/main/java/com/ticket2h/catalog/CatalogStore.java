package com.ticket2h.catalog;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import com.ticket2h.common.time.Times;

/**
 * Truy cập bảng của module catalog bằng SQL thuần. Không dùng JPA entity cho {@code ticket_types}
 * và {@code seats} vì luồng đặt vé cập nhật các bảng này bằng câu lệnh có điều kiện: một entity
 * giữ giá trị cũ của {@code available_quantity} rồi flush sẽ ghi đè số vé vừa bán.
 */
@Repository
class CatalogStore {

    private final NamedParameterJdbcTemplate jdbc;

    CatalogStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- venues ----------

    void insertVenue(UUID id, UUID organizerId, String name, String address, String city, Instant now) {
        jdbc.update("""
                INSERT INTO venues (id, organizer_id, name, address, city, created_at)
                VALUES (:id, :organizerId, :name, :address, :city, :now)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("organizerId", organizerId).addValue("name", name)
                .addValue("address", address).addValue("city", city).addValue("now", Times.utc(now)));
    }

    Optional<VenueRow> findVenue(UUID id) {
        return jdbc.query("SELECT * FROM venues WHERE id = :id", Map.of("id", id), CatalogStore::venue)
                .stream().findFirst();
    }

    List<VenueRow> venuesOf(UUID organizerId) {
        return jdbc.query("SELECT * FROM venues WHERE organizer_id = :organizerId ORDER BY created_at",
                Map.of("organizerId", organizerId), CatalogStore::venue);
    }

    // ---------- events ----------

    void insertEvent(EventRow e) {
        jdbc.update("""
                INSERT INTO events (id, organizer_id, venue_id, title, description, time_zone, starts_at,
                                    sale_opens_at, sale_closes_at, status, search_text, max_tickets_per_user,
                                    created_at, updated_at)
                VALUES (:id, :organizerId, :venueId, :title, :description, :timeZone, :startsAt,
                        :saleOpensAt, :saleClosesAt, :status, :searchText, :maxTicketsPerUser,
                        :createdAt, :updatedAt)
                """, eventParams(e));
    }

    void updateEvent(EventRow e) {
        jdbc.update("""
                UPDATE events
                SET venue_id = :venueId, title = :title, description = :description, starts_at = :startsAt,
                    sale_opens_at = :saleOpensAt, sale_closes_at = :saleClosesAt, search_text = :searchText,
                    max_tickets_per_user = :maxTicketsPerUser, updated_at = :updatedAt
                WHERE id = :id
                """, eventParams(e));
    }

    /** Khoá dòng sự kiện để các thay đổi của ban tổ chức (thêm loại vé, công bố) chạy lần lượt. */
    Optional<EventRow> lockEvent(UUID id) {
        return jdbc.query("SELECT * FROM events WHERE id = :id FOR UPDATE", Map.of("id", id), CatalogStore::event)
                .stream().findFirst();
    }

    Optional<EventRow> findEvent(UUID id) {
        return jdbc.query("SELECT * FROM events WHERE id = :id", Map.of("id", id), CatalogStore::event)
                .stream().findFirst();
    }

    int markPublished(UUID id, Instant now) {
        return jdbc.update("""
                UPDATE events SET status = 'PUBLISHED', updated_at = :now
                WHERE id = :id AND status = 'DRAFT'
                """, Map.of("id", id, "now", Times.utc(now)));
    }

    List<EventRow> eventsOf(UUID organizerId) {
        return jdbc.query("SELECT * FROM events WHERE organizer_id = :organizerId ORDER BY starts_at",
                Map.of("organizerId", organizerId), CatalogStore::event);
    }

    /** Sự kiện đã công bố có search_text chứa mọi từ trong {@code tokens} (đã bỏ dấu). */
    List<EventListingRow> searchPublished(List<String> tokens, int limit, int offset) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", limit).addValue("offset", offset);
        String where = tokenFilter(tokens, params);
        return jdbc.query("""
                SELECT e.id, e.title, e.starts_at, e.time_zone, e.sale_opens_at, e.sale_closes_at,
                       v.name AS venue_name, v.city AS venue_city
                FROM events e JOIN venues v ON v.id = e.venue_id
                WHERE e.status = 'PUBLISHED'""" + where + """
                 ORDER BY e.starts_at, e.id
                LIMIT :limit OFFSET :offset
                """, params, (rs, i) -> new EventListingRow(
                        rs.getObject("id", UUID.class), rs.getString("title"), instant(rs, "starts_at"),
                        ZoneId.of(rs.getString("time_zone")), instant(rs, "sale_opens_at"),
                        instant(rs, "sale_closes_at"), rs.getString("venue_name"), rs.getString("venue_city")));
    }

    long countPublished(List<String> tokens) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = tokenFilter(tokens, params);
        Long count = jdbc.queryForObject("SELECT count(*) FROM events e WHERE e.status = 'PUBLISHED'" + where,
                params, Long.class);
        return count == null ? 0 : count;
    }

    private static String tokenFilter(List<String> tokens, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            where.append(" AND e.search_text LIKE :t").append(i);
            params.addValue("t" + i, "%" + tokens.get(i) + "%");
        }
        return where.toString();
    }

    // ---------- ticket types ----------

    void insertTicketType(UUID id, UUID eventId, String name, TicketKind kind, BigDecimal price,
            Integer quantity, Instant now) {
        jdbc.update("""
                INSERT INTO ticket_types (id, event_id, name, kind, price, total_quantity, available_quantity,
                                          created_at)
                VALUES (:id, :eventId, :name, :kind, :price, :quantity, :quantity, :now)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("eventId", eventId).addValue("name", name)
                .addValue("kind", kind.name()).addValue("price", price).addValue("quantity", quantity)
                .addValue("now", Times.utc(now)));
    }

    List<TicketTypeRow> ticketTypesOf(UUID eventId) {
        return jdbc.query("""
                SELECT id, event_id, name, kind, price, total_quantity, available_quantity
                FROM ticket_types WHERE event_id = :eventId ORDER BY created_at, name, id
                """, Map.of("eventId", eventId), (rs, i) -> new TicketTypeRow(
                        rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class), rs.getString("name"),
                        TicketKind.valueOf(rs.getString("kind")), rs.getBigDecimal("price"),
                        (Integer) rs.getObject("total_quantity"), (Integer) rs.getObject("available_quantity")));
    }

    boolean ticketTypeNameExists(UUID eventId, String name) {
        return !jdbc.queryForList("SELECT 1 FROM ticket_types WHERE event_id = :eventId AND name = :name",
                Map.of("eventId", eventId, "name", name), Integer.class).isEmpty();
    }

    // ---------- seats ----------

    int insertSeats(UUID eventId, UUID ticketTypeId, List<SeatPosition> positions) {
        SqlParameterSource[] batch = positions.stream()
                .map(p -> new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID()).addValue("eventId", eventId)
                        .addValue("ticketTypeId", ticketTypeId).addValue("section", p.section())
                        .addValue("rowLabel", p.rowLabel()).addValue("seatNumber", p.seatNumber()))
                .toArray(SqlParameterSource[]::new);
        int[] counts = jdbc.batchUpdate("""
                INSERT INTO seats (id, event_id, ticket_type_id, section, row_label, seat_number)
                VALUES (:id, :eventId, :ticketTypeId, :section, :rowLabel, :seatNumber)
                """, batch);
        return counts.length;
    }

    /** Vị trí mọi ghế đã có của sự kiện (để báo trùng theo từng dòng khi nhập file, trước khi INSERT). */
    Set<SeatPosition> seatPositions(UUID eventId) {
        Set<SeatPosition> positions = new HashSet<>();
        jdbc.query("SELECT section, row_label, seat_number FROM seats WHERE event_id = :eventId",
                Map.of("eventId", eventId), rs -> {
                    positions.add(new SeatPosition(rs.getString("section"), rs.getString("row_label"),
                            rs.getInt("seat_number")));
                });
        return positions;
    }

    /** Số ghế theo từng loại vé của sự kiện. */
    Map<UUID, Integer> seatCounts(UUID eventId) {
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.query("SELECT ticket_type_id, count(*) AS n FROM seats WHERE event_id = :eventId GROUP BY ticket_type_id",
                Map.of("eventId", eventId),
                rs -> {
                    counts.put(rs.getObject("ticket_type_id", UUID.class), rs.getInt("n"));
                });
        return counts;
    }

    /** Số ghế còn giữ được tại {@code now}, tính cả ghế HELD đã quá hạn (hết hạn lười). */
    Map<UUID, Integer> availableSeatCounts(UUID eventId, Instant now) {
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.query("""
                SELECT ticket_type_id, count(*) AS n FROM seats
                WHERE event_id = :eventId
                  AND (status = 'AVAILABLE' OR (status = 'HELD' AND held_until < :now))
                GROUP BY ticket_type_id
                """, Map.of("eventId", eventId, "now", Times.utc(now)),
                rs -> {
                    counts.put(rs.getObject("ticket_type_id", UUID.class), rs.getInt("n"));
                });
        return counts;
    }

    /** seatId → ticketTypeId cho các ghế thuộc sự kiện. Ghế không thuộc sự kiện thì không có trong map. */
    Map<UUID, UUID> seatTicketTypes(UUID eventId, List<UUID> seatIds) {
        Map<UUID, UUID> result = new HashMap<>();
        if (seatIds.isEmpty()) {
            return result;
        }
        jdbc.query("SELECT id, ticket_type_id FROM seats WHERE event_id = :eventId AND id IN (:seatIds)",
                Map.of("eventId", eventId, "seatIds", seatIds),
                rs -> {
                    result.put(rs.getObject("id", UUID.class), rs.getObject("ticket_type_id", UUID.class));
                });
        return result;
    }

    // ---------- staff ----------

    void assignStaff(UUID eventId, UUID userId) {
        jdbc.update("""
                INSERT INTO event_staff (event_id, user_id) VALUES (:eventId, :userId)
                ON CONFLICT DO NOTHING
                """, Map.of("eventId", eventId, "userId", userId));
    }

    void removeStaff(UUID eventId, UUID userId) {
        jdbc.update("DELETE FROM event_staff WHERE event_id = :eventId AND user_id = :userId",
                Map.of("eventId", eventId, "userId", userId));
    }

    List<UUID> staffOf(UUID eventId) {
        return jdbc.queryForList("SELECT user_id FROM event_staff WHERE event_id = :eventId ORDER BY user_id",
                Map.of("eventId", eventId), UUID.class);
    }

    boolean isStaff(UUID eventId, UUID userId) {
        return !jdbc.queryForList("SELECT 1 FROM event_staff WHERE event_id = :eventId AND user_id = :userId",
                Map.of("eventId", eventId, "userId", userId), Integer.class).isEmpty();
    }

    // ---------- mapping ----------

    private static MapSqlParameterSource eventParams(EventRow e) {
        return new MapSqlParameterSource()
                .addValue("id", e.id()).addValue("organizerId", e.organizerId()).addValue("venueId", e.venueId())
                .addValue("title", e.title()).addValue("description", e.description())
                .addValue("timeZone", e.timeZone().getId()).addValue("startsAt", Times.utc(e.startsAt()))
                .addValue("saleOpensAt", Times.utc(e.saleOpensAt()))
                .addValue("saleClosesAt", Times.utc(e.saleClosesAt()))
                .addValue("status", e.status().name()).addValue("searchText", e.searchText())
                .addValue("maxTicketsPerUser", e.maxTicketsPerUser())
                .addValue("createdAt", Times.utc(e.createdAt())).addValue("updatedAt", Times.utc(e.updatedAt()));
    }

    private static VenueRow venue(ResultSet rs, int i) throws SQLException {
        return new VenueRow(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                rs.getString("name"), rs.getString("address"), rs.getString("city"));
    }

    private static EventRow event(ResultSet rs, int i) throws SQLException {
        return new EventRow(rs.getObject("id", UUID.class), rs.getObject("organizer_id", UUID.class),
                rs.getObject("venue_id", UUID.class), rs.getString("title"), rs.getString("description"),
                ZoneId.of(rs.getString("time_zone")), instant(rs, "starts_at"), instant(rs, "sale_opens_at"),
                instant(rs, "sale_closes_at"), EventStatus.valueOf(rs.getString("status")),
                rs.getString("search_text"), rs.getInt("max_tickets_per_user"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    record SeatPosition(String section, String rowLabel, int seatNumber) {}
}
