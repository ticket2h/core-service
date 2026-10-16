package com.ticket2h.catalog;

import java.util.UUID;

/** Địa điểm tổ chức. */
public record VenueRow(UUID id, UUID organizerId, String name, String address, String city) {}
