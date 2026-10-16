package com.ticket2h.dev;

import java.util.Map;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.common.security.AuthUser;
import com.ticket2h.ticketing.TicketQueries;

/**
 * Chỉ có ở profile {@code dev}: trả chuỗi token QR để thử check-in bằng curl mà không cần quét ảnh.
 * Không bao giờ bật ở môi trường thật.
 */
@RestController
@Profile("dev")
class DevTicketController {

    private final TicketQueries tickets;

    DevTicketController(TicketQueries tickets) {
        this.tickets = tickets;
    }

    @GetMapping("/dev/tickets/{ticketId}/qr-token")
    Map<String, String> qrToken(AuthUser caller, @PathVariable UUID ticketId) {
        return Map.of("qrToken", tickets.qrToken(tickets.ownedTicket(caller.id(), ticketId)));
    }
}
