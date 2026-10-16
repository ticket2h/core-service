package com.ticket2h.common.events;

import java.time.Instant;
import java.util.UUID;

public record OrderPaid(UUID orderId, UUID userId, UUID eventId, Instant paidAt) {}
