package com.ticket2h.payment;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Nhận webhook: ghi log → kiểm tra chữ ký → parse → xử lý. KHÔNG có {@code @Transactional}.
 *
 * <p>Mã trả về cho cổng: sai chữ ký 401; xử lý xong (kể cả trùng) 200; không tìm thấy payment hay
 * lệch số tiền cũng 200 vì gửi lại không giải quyết được; lỗi tạm thời (mất kết nối DB) 500 để cổng gửi lại.
 */
@Service
class WebhookIngestion {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestion.class);

    private final WebhookSignatureVerifier verifier;
    private final WebhookLogWriter logs;
    private final PaymentWebhookProcessor processor;
    private final JsonMapper json;
    private final Clock clock;

    WebhookIngestion(WebhookSignatureVerifier verifier, WebhookLogWriter logs, PaymentWebhookProcessor processor,
            JsonMapper json, Clock clock) {
        this.verifier = verifier;
        this.logs = logs;
        this.processor = processor;
        this.json = json;
        this.clock = clock;
    }

    void handle(String eventIdHeader, String timestamp, String signature, byte[] body) {
        String rawBody = new String(body, StandardCharsets.UTF_8);
        try {
            verifier.verify(timestamp, signature, body);
        } catch (InvalidWebhookSignatureException e) {
            logs.record(Payment.PROVIDER_MOCKPAY, eventIdHeader, false, rawBody, "INVALID_SIGNATURE", clock.instant());
            log.warn("Từ chối webhook {}: {}", eventIdHeader, e.getMessage());
            throw e;
        }

        MockPayWebhookEvent event;
        try {
            event = json.readValue(body, MockPayWebhookEvent.class);
        } catch (JacksonException e) {
            logs.record(Payment.PROVIDER_MOCKPAY, eventIdHeader, true, rawBody, "MALFORMED_PAYLOAD", clock.instant());
            log.error("Webhook {} có chữ ký đúng nhưng body không đọc được", eventIdHeader);
            return;
        }

        String eventId = event.eventId() != null ? event.eventId() : eventIdHeader;
        long logId = logs.record(Payment.PROVIDER_MOCKPAY, eventId, true, rawBody, "RECEIVED", clock.instant());
        try {
            WebhookOutcome outcome = processor.process(event);
            logs.updateResult(logId, outcome.name());
            log.info("Webhook {} ({}) cho payment {}: {}", eventId, event.type(), event.merchantRef(), outcome);
        } catch (RuntimeException e) {
            try {
                logs.updateResult(logId, "ERROR");
            } catch (RuntimeException ignored) {
                // database đang lỗi thì cũng không ghi được log; cổng sẽ gửi lại
            }
            throw e;   // → 500, cổng gửi lại
        }
    }
}
