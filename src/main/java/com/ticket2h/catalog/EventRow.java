package com.ticket2h.catalog;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/** Một dòng của bảng events. */
public record EventRow(UUID id, UUID organizerId, UUID venueId, String title, String description, ZoneId timeZone,
        Instant startsAt, Instant saleOpensAt, Instant saleClosesAt, EventStatus status, String searchText,
        int maxTicketsPerUser, Instant createdAt, Instant updatedAt) {}
