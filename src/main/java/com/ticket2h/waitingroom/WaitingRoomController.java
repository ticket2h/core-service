package com.ticket2h.waitingroom;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.common.ratelimit.RateLimited;
import com.ticket2h.common.security.AuthUser;

/** Phòng chờ (ADR-027). Cần đăng nhập: thứ tự gắn với tài khoản, không với trình duyệt. */
@RestController
class WaitingRoomController {

    private final WaitingRoom waitingRoom;
    private final CatalogQueries catalog;

    WaitingRoomController(WaitingRoom waitingRoom, CatalogQueries catalog) {
        this.waitingRoom = waitingRoom;
        this.catalog = catalog;
    }

    @PostMapping("/events/{eventId}/waiting-room")
    @RateLimited(RateLimited.Policy.WAITING_ROOM)
    QueueStatus join(AuthUser caller, @PathVariable UUID eventId) {
        return waitingRoom.join(caller.id(), eventId);
    }

    @GetMapping("/events/{eventId}/waiting-room")
    @RateLimited(RateLimited.Policy.WAITING_ROOM)
    QueueStatus status(AuthUser caller, @PathVariable UUID eventId) {
        return waitingRoom.status(caller.id(), eventId);
    }

    @GetMapping("/organizer/events/{eventId}/waiting-room")
    WaitingRoom.WaitingRoomStats stats(AuthUser caller, @PathVariable UUID eventId) {
        return waitingRoom.stats(catalog.ownedEvent(caller.organizerId(), eventId));
    }
}
