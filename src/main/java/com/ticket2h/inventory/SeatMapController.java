package com.ticket2h.inventory;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.catalog.EventStatus;
import com.ticket2h.common.error.NotFoundException;
import com.ticket2h.common.time.Times;

/**
 * Sơ đồ ghế công khai. Trả trạng thái hiệu lực: ghế HELD đã quá hạn hiển thị AVAILABLE.
 * Không trả {@code order_id} hay {@code held_until} (không lộ ai đang giữ, giữ đến bao giờ).
 */
@RestController
class SeatMapController {

    private final CatalogQueries catalog;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    SeatMapController(CatalogQueries catalog, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/events/{eventId}/seats")
    List<SeatView> seats(@PathVariable UUID eventId) {
        catalog.findEvent(eventId)
                .filter(e -> e.status() == EventStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("sự kiện"));
        return jdbc.query("""
                SELECT id, ticket_type_id, section, row_label, seat_number,
                       CASE WHEN status = 'HELD' AND held_until < :now THEN 'AVAILABLE' ELSE status END AS status
                FROM seats
                WHERE event_id = :eventId
                ORDER BY section, row_label, seat_number
                """, Map.of("eventId", eventId, "now", Times.utc(clock.instant())),
                (rs, i) -> new SeatView(rs.getObject("id", UUID.class), rs.getObject("ticket_type_id", UUID.class),
                        rs.getString("section"), rs.getString("row_label"), rs.getInt("seat_number"),
                        rs.getString("status")));
    }

    record SeatView(UUID seatId, UUID ticketTypeId, String section, String row, int number, String status) {}
}
