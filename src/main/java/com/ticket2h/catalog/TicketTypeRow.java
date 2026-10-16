package com.ticket2h.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/** Loại vé. {@code totalQuantity}/{@code availableQuantity} chỉ có với vé GA. */
public record TicketTypeRow(UUID id, UUID eventId, String name, TicketKind kind, BigDecimal price,
        Integer totalQuantity, Integer availableQuantity) {}
