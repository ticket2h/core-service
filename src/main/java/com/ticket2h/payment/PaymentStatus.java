package com.ticket2h.payment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    INITIATED, SUCCEEDED, FAILED, AMOUNT_MISMATCH, REFUND_PENDING, REFUNDED;

    /** FAILED → SUCCEEDED: webhook đến sai thứ tự, tiền đã trừ là sự thật. */
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED = Map.of(
            INITIATED, EnumSet.of(SUCCEEDED, FAILED, AMOUNT_MISMATCH),
            FAILED, EnumSet.of(SUCCEEDED, AMOUNT_MISMATCH),
            SUCCEEDED, EnumSet.of(REFUND_PENDING),
            REFUND_PENDING, EnumSet.of(REFUNDED),
            AMOUNT_MISMATCH, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    public boolean canTransitionTo(PaymentStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** Tiền đã trừ phía cổng thanh toán (đã ghi nhận ít nhất một lần webhook thành công). */
    public boolean isCaptured() {
        return this == SUCCEEDED || this == REFUND_PENDING || this == REFUNDED;
    }
}
