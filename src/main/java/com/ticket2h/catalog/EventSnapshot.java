package com.ticket2h.catalog;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/** Thông tin sự kiện mà các module khác cần (đặt vé, check-in, chuyển nhượng). */
public record EventSnapshot(
        UUID id,
        UUID organizerId,
        String title,
        EventStatus status,
        Instant startsAt,
        Instant saleOpensAt,
        Instant saleClosesAt,
        int maxTicketsPerUser,
        ZoneId timeZone,
        Integer waitingRoomAdmitPerSecond) {

    /** Có phòng chờ (ADR-027): trong giờ bán, đặt vé phải có vé vào cửa. */
    public boolean hasWaitingRoom() {
        return waitingRoomAdmitPerSecond != null;
    }

    public boolean isOnSale(Instant now) {
        return status == EventStatus.PUBLISHED && !now.isBefore(saleOpensAt) && now.isBefore(saleClosesAt);
    }

    public void assertOnSale(Instant now) {
        if (!isOnSale(now)) {
            throw new SaleNotOpenException();
        }
    }
}
