package com.ticket2h.payment;

import java.math.BigDecimal;
import java.time.Instant;

/** Body webhook của MockPay. Chỉ parse SAU khi đã kiểm tra chữ ký trên body thô. */
record MockPayWebhookEvent(
        String eventId,
        String type,
        String gatewayPaymentId,
        String gatewayTransactionId,
        String merchantRef,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {

    static final String SUCCEEDED = "payment.succeeded";
    static final String FAILED = "payment.failed";
}
