package com.ticket2h.ticketing;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/** Vé kèm thông tin hiển thị. {@code seatLabel} dạng "VIP A-12" (khu, hàng-số), rỗng với vé GA. */
public record TicketView(
        UUID ticketId,
        UUID orderId,
        UUID eventId,
        UUID ticketTypeId,
        UUID seatId,
        UUID ownerUserId,
        TicketStatus status,
        int tokenVersion,
        Instant issuedAt,
        Instant checkedInAt,
        String checkedInGate,
        String eventTitle,
        Instant eventStartsAt,
        ZoneId eventTimeZone,
        String ticketTypeName,
        String seatLabel) {

    public TicketClaims claims() {
        return new TicketClaims(ticketId, eventId, tokenVersion);
    }
}
