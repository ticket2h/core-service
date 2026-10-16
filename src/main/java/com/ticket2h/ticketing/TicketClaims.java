package com.ticket2h.ticketing;

import java.nio.ByteBuffer;
import java.util.UUID;

/** Nội dung được ký trong QR: 16 byte ticketId + 16 byte eventId + 4 byte tokenVersion = 36 byte. */
public record TicketClaims(UUID ticketId, UUID eventId, int tokenVersion) {

    static final int LENGTH = 36;

    byte[] toBytes() {
        return ByteBuffer.allocate(LENGTH)
                .putLong(ticketId.getMostSignificantBits()).putLong(ticketId.getLeastSignificantBits())
                .putLong(eventId.getMostSignificantBits()).putLong(eventId.getLeastSignificantBits())
                .putInt(tokenVersion)
                .array();
    }

    static TicketClaims fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        UUID ticketId = new UUID(buffer.getLong(), buffer.getLong());
        UUID eventId = new UUID(buffer.getLong(), buffer.getLong());
        return new TicketClaims(ticketId, eventId, buffer.getInt());
    }
}
