package com.ticket2h.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ticket2h.common.error.ForbiddenException;
import com.ticket2h.common.error.NotFoundException;

/** API đọc công khai của module catalog cho các module khác. Không khoá dữ liệu. */
@Service
public class CatalogQueries {

    private final CatalogStore store;

    CatalogQueries(CatalogStore store) {
        this.store = store;
    }

    public Optional<EventSnapshot> findEvent(UUID eventId) {
        return store.findEvent(eventId).map(CatalogQueries::snapshot);
    }

    /** Sự kiện có phòng chờ đang trong giờ bán. */
    public List<EventSnapshot> eventsWithOpenWaitingRoom(Instant now) {
        return store.eventsWithOpenWaitingRoom(now).stream().map(CatalogQueries::snapshot).toList();
    }

    private static EventSnapshot snapshot(EventRow e) {
        return new EventSnapshot(e.id(), e.organizerId(), e.title(), e.status(), e.startsAt(), e.saleOpensAt(),
                e.saleClosesAt(), e.maxTicketsPerUser(), e.timeZone(), e.waitingRoomAdmitPerSecond());
    }

    /**
     * Sự kiện của đúng ban tổ chức đang gọi, cho các API ban tổ chức ở module khác (báo cáo, xuất file). Cùng quy tắc
     * với {@link OrganizerCatalogService}: không có thì 404, thuộc ban tổ chức khác thì 403.
     */
    public EventSnapshot ownedEvent(UUID organizerId, UUID eventId) {
        EventSnapshot event = findEvent(eventId).orElseThrow(() -> new NotFoundException("sự kiện"));
        if (!event.organizerId().equals(organizerId)) {
            throw new ForbiddenException("Sự kiện thuộc ban tổ chức khác");
        }
        return event;
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
