package com.ticket2h.catalog;

import java.math.BigDecimal;
import java.util.UUID;

public record TicketTypeInfo(UUID id, UUID eventId, String name, TicketKind kind, BigDecimal price) {}
