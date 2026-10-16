package com.ticket2h.ordering;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum OrderStatus {
    PENDING_PAYMENT, PAID, FULFILLED, EXPIRED, CANCELLED;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            PENDING_PAYMENT, EnumSet.of(PAID, EXPIRED, CANCELLED),
            PAID, EnumSet.of(FULFILLED),
            FULFILLED, EnumSet.noneOf(OrderStatus.class),
            EXPIRED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class));

    public boolean canTransitionTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}
