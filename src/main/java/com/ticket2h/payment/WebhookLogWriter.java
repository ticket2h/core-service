package com.ticket2h.payment;

import java.time.Instant;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.time.Times;

/**
 * Lưu nguyên văn mọi webhook nhận được, kể cả sai chữ ký. Transaction riêng ({@code REQUIRES_NEW})
 * để log vẫn còn khi xử lý nghiệp vụ lỗi và rollback.
 */
@Component
class WebhookLogWriter {

    private final NamedParameterJdbcTemplate jdbc;

    WebhookLogWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    long record(String provider, String providerEventId, boolean signatureValid, String body, String result,
            Instant receivedAt) {
        Long id = jdbc.queryForObject("""
                INSERT INTO payment_webhook_logs (provider, provider_event_id, signature_valid, body, result, received_at)
                VALUES (:provider, :eventId, :valid, :body, :result, :receivedAt)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("provider", provider)
                .addValue("eventId", truncate(providerEventId, 64))
                .addValue("valid", signatureValid)
                .addValue("body", textSafe(body))
                .addValue("result", result)
                .addValue("receivedAt", Times.utc(receivedAt)), Long.class);
        return id == null ? -1 : id;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void updateResult(long id, String result) {
        jdbc.update("UPDATE payment_webhook_logs SET result = :result WHERE id = :id",
                Map.of("result", truncate(result, 40), "id", id));
    }

    /** PostgreSQL không lưu được ký tự NUL trong cột text; body sai chữ ký có thể chứa bất cứ gì. */
    private static String textSafe(String body) {
        return body.indexOf('\u0000') < 0 ? body : body.replace('\u0000', '\uFFFD');
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
