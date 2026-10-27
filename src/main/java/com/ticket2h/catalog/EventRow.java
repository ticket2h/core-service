package com.ticket2h.catalog;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Một dòng của bảng events. {@code waitingRoomAdmitPerSecond}: {@code null} là không có phòng chờ (ADR-027); chỉ đổi
 * bằng {@code CatalogStore.setWaitingRoom}, không qua {@code updateEvent}.
 */
public record EventRow(UUID id, UUID organizerId, UUID venueId, String title, String description, ZoneId timeZone,
        Instant startsAt, Instant saleOpensAt, Instant saleClosesAt, EventStatus status, String searchText,
        int maxTicketsPerUser, Instant createdAt, Instant updatedAt, Integer waitingRoomAdmitPerSecond) {}
