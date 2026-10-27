package com.ticket2h.inventory;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.catalog.EventStatus;
import com.ticket2h.common.cache.Caches;
import com.ticket2h.common.cache.RefreshAheadCache;
import com.ticket2h.common.error.NotFoundException;
import com.ticket2h.common.time.Times;

import tools.jackson.databind.json.JsonMapper;

/** Ảnh chụp sơ đồ ghế của sự kiện đã công bố (ADR-024). */
@Service
public class SeatMapQueries {

    private final CatalogQueries catalog;
    private final NamedParameterJdbcTemplate jdbc;
    private final SeatChangeBus bus;
    private final JsonMapper json;
    private final Clock clock;
    private final RefreshAheadCache<UUID, SeatMapSnapshot> cache;

    SeatMapQueries(CatalogQueries catalog, NamedParameterJdbcTemplate jdbc, SeatChangeBus bus, JsonMapper json,
            Clock clock, Caches caches) {
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.bus = bus;
        this.json = json;
        this.clock = clock;
        this.cache = caches.seatMap(snapshot -> snapshot.json().length, this::load);
    }

    /** Có thể cũ tối đa khoảng {@code ticket2h.cache.seat-map-ttl} (ADR-025). */
    SeatMapSnapshot snapshot(UUID eventId) {
        return cache.get(eventId);
    }

    /**
     * Ghế đang giữ nhưng quá {@code held_until} hiện là trống (hết hạn lười, như lúc giữ ghế), {@code version} giữ
     * nguyên. Đọc số thứ tự TRƯỚC khi đọc ghế: message số k chỉ được phát sau khi transaction của nó commit, nên
     * {@code k <= seq} nghĩa là câu SELECT sau đó thấy thay đổi đó.
     */
    private SeatMapSnapshot load(UUID eventId) {
        catalog.findEvent(eventId)
                .filter(e -> e.status() == EventStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("sự kiện"));
        long seq = bus.currentSequence(eventId);
        List<SeatView> seats = jdbc.query("""
                SELECT id, ticket_type_id, section, row_label, seat_number, version,
                       CASE WHEN status = 'HELD' AND held_until < :now THEN 'AVAILABLE' ELSE status END AS status
                FROM seats
                WHERE event_id = :eventId
                ORDER BY section, row_label, seat_number
                """, Map.of("eventId", eventId, "now", Times.utc(clock.instant())),
                (rs, i) -> new SeatView(rs.getObject("id", UUID.class), rs.getObject("ticket_type_id", UUID.class),
                        rs.getString("section"), rs.getString("row_label"), rs.getInt("seat_number"),
                        rs.getString("status"), rs.getLong("version")));
        byte[] body = json.writeValueAsBytes(seats);
        return new SeatMapSnapshot(seq, body, '"' + DigestUtils.md5DigestAsHex(body) + '"');
    }

    /** {@code version}: số phiên bản của ghế, cùng nghĩa với {@link SeatChange#version()}. */
    public record SeatView(UUID seatId, UUID ticketTypeId, String section, String row, int number, String status,
            long version) {}
}
