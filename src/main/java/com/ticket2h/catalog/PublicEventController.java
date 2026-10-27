package com.ticket2h.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/** Xem và tìm sự kiện đã công bố. Không cần đăng nhập. */
@RestController
class PublicEventController {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE = 10_000;

    private final CatalogStore store;
    private final PublicEventDetails details;

    PublicEventController(CatalogStore store, PublicEventDetails details) {
        this.store = store;
        this.details = details;
    }

    /** {@code GET /events?q=son tung} tìm không dấu, không phân biệt hoa thường. */
    @GetMapping("/events")
    @Transactional(readOnly = true)
    EventPage search(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.clamp(page, 0, MAX_PAGE);   // page * size không tràn int
        List<String> tokens = VietnameseText.tokens(q);
        List<EventSummary> items = store.searchPublished(tokens, safeSize, safePage * safeSize).stream()
                .map(EventSummary::of).toList();
        return new EventPage(items, safePage, safeSize, store.countPublished(tokens));
    }

    /** Có thể cũ tối đa {@code ticket2h.cache.event-ttl} (cả số vé còn và {@code onSale}), ADR-025. */
    @GetMapping("/events/{eventId}")
    EventDetail event(@PathVariable UUID eventId) {
        return details.of(eventId);
    }

    record EventPage(List<EventSummary> items, int page, int size, long total) {}

    record EventSummary(UUID id, String title, Instant startsAt, String timeZone, Instant saleOpensAt,
            Instant saleClosesAt, String venueName, String city) {
        static EventSummary of(EventListingRow r) {
            return new EventSummary(r.id(), r.title(), r.startsAt(), r.timeZone().getId(), r.saleOpensAt(),
                    r.saleClosesAt(), r.venueName(), r.venueCity());
        }
    }

    /** {@code waitingRoom}: lúc đang bán phải xếp hàng (ADR-027) trước khi đặt vé. */
    record EventDetail(UUID id, String title, String description, String timeZone, Instant startsAt,
            Instant saleOpensAt, Instant saleClosesAt, int maxTicketsPerUser, boolean onSale, boolean waitingRoom,
            VenueView venue, List<TicketTypeView> ticketTypes) {}

    record VenueView(String name, String address, String city) {}

    record TicketTypeView(UUID id, String name, TicketKind kind, BigDecimal price, int available) {
        static TicketTypeView of(TicketTypeRow t, int availableSeats) {
            int available = t.kind() == TicketKind.GA ? t.availableQuantity() : availableSeats;
            return new TicketTypeView(t.id(), t.name(), t.kind(), t.price(), available);
        }
    }
}
