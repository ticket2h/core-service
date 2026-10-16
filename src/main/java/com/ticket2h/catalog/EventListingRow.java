package com.ticket2h.catalog;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/** Sự kiện trong kết quả tìm kiếm, kèm tên địa điểm. */
public record EventListingRow(UUID id, String title, Instant startsAt, ZoneId timeZone, Instant saleOpensAt,
        Instant saleClosesAt, String venueName, String venueCity) {}
