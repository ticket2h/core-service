package com.ticket2h.catalog;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.ticket2h.catalog.PublicEventController.EventDetail;
import com.ticket2h.catalog.PublicEventController.TicketTypeView;
import com.ticket2h.catalog.PublicEventController.VenueView;
import com.ticket2h.common.cache.Caches;
import com.ticket2h.common.cache.RefreshAheadCache;
import com.ticket2h.common.error.NotFoundException;

/**
 * Chi tiết sự kiện đã công bố cho trang công khai, qua cache (ADR-025). Sự kiện nháp, không tồn tại: không cache.
 * Không có transaction: bốn câu SELECT độc lập (READ COMMITTED vốn cũng cho mỗi câu một ảnh chụp riêng), và lần tải
 * chạy trên luồng nền của cache.
 */
@Component
class PublicEventDetails {

    private final CatalogStore store;
    private final Clock clock;
    private final RefreshAheadCache<UUID, EventDetail> cache;

    PublicEventDetails(CatalogStore store, Clock clock, Caches caches) {
        this.store = store;
        this.clock = clock;
        this.cache = caches.eventDetail(this::load);
    }

    EventDetail of(UUID eventId) {
        return cache.get(eventId);
    }

    private EventDetail load(UUID eventId) {
        EventRow event = store.findEvent(eventId)
                .filter(e -> e.status() == EventStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("sự kiện"));
        VenueRow venue = store.findVenue(event.venueId()).orElseThrow();
        Instant now = clock.instant();
        Map<UUID, Integer> availableSeats = store.availableSeatCounts(eventId, now);
        List<TicketTypeView> types = store.ticketTypesOf(eventId).stream()
                .map(t -> TicketTypeView.of(t, availableSeats.getOrDefault(t.id(), 0)))
                .toList();
        return new EventDetail(event.id(), event.title(), event.description(), event.timeZone().getId(),
                event.startsAt(), event.saleOpensAt(), event.saleClosesAt(), event.maxTicketsPerUser(),
                !now.isBefore(event.saleOpensAt()) && now.isBefore(event.saleClosesAt()),
                event.waitingRoomAdmitPerSecond() != null,
                new VenueView(venue.name(), venue.address(), venue.city()), types);
    }
}
