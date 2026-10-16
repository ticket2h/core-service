package com.ticket2h.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

/** API đọc công khai của module catalog cho các module khác. Không khoá dữ liệu. */
@Service
public class CatalogQueries {

    private final CatalogStore store;

    CatalogQueries(CatalogStore store) {
        this.store = store;
    }

    public Optional<EventSnapshot> findEvent(UUID eventId) {
        return store.findEvent(eventId).map(e -> new EventSnapshot(e.id(), e.organizerId(), e.title(), e.status(),
                e.startsAt(), e.saleOpensAt(), e.saleClosesAt(), e.maxTicketsPerUser(), e.timeZone()));
    }

    public List<TicketTypeInfo> ticketTypes(UUID eventId) {
        return store.ticketTypesOf(eventId).stream()
                .map(t -> new TicketTypeInfo(t.id(), t.eventId(), t.name(), t.kind(), t.price()))
                .toList();
    }

    /** seatId → ticketTypeId. Ghế không tồn tại hoặc thuộc sự kiện khác thì không có trong kết quả. */
    public Map<UUID, UUID> seatTicketTypes(UUID eventId, List<UUID> seatIds) {
        return store.seatTicketTypes(eventId, seatIds);
    }

    /** Nhân viên có được phân công soát vé sự kiện này không. */
    public boolean isAssignedStaff(UUID eventId, UUID userId) {
        return store.isStaff(eventId, userId);
    }
}
