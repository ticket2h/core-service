package com.ticket2h.common.events;

import java.util.List;
import java.util.UUID;

public record TicketsIssued(UUID orderId, UUID userId, List<UUID> ticketIds) {}
