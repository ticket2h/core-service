package com.ticket2h.payment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.ticket2h.common.config.Ticket2hProperties;

/**
 * Kiểm tra {@code X-MockPay-Signature = hex(HMAC_SHA256(secret, timestamp + "." + rawBody))}.
 *
 * <ul>
 *   <li>Tính trên body thô dạng byte. Parse JSON rồi serialize lại thì chỉ cần khác một dấu cách là sai.</li>
 *   <li>Timestamp lệch quá 5 phút so với {@code clock} → từ chối (chống phát lại webhook cũ).</li>
 *   <li>So sánh bằng {@link MessageDigest#isEqual}: thời gian so sánh không phụ thuộc vị trí byte sai.</li>
 * </ul>
 */
@Component
public class WebhookSignatureVerifier {

    private static final Duration MAX_SKEW = Duration.ofMinutes(5);

    private final byte[] secret;
    private final Clock clock;

    public WebhookSignatureVerifier(Ticket2hProperties properties, Clock clock) {
        this.secret = properties.payment().mockpay().webhookSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public void verify(String timestampHeader, String signatureHeader, byte[] rawBody) {
        if (timestampHeader == null || signatureHeader == null || rawBody == null) {
            throw new InvalidWebhookSignatureException("missing headers");
        }
        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestampHeader);
        } catch (NumberFormatException e) {
            throw new InvalidWebhookSignatureException("malformed timestamp");
        }
        Duration skew;
        try {
            skew = Duration.between(Instant.ofEpochSecond(epochSeconds), clock.instant()).abs();
        } catch (ArithmeticException | java.time.DateTimeException e) {
            throw new InvalidWebhookSignatureException("malformed timestamp");
        }
        if (skew.compareTo(MAX_SKEW) > 0) {
            throw new InvalidWebhookSignatureException("timestamp outside tolerance");
        }

        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHeader);
        } catch (IllegalArgumentException e) {
            throw new InvalidWebhookSignatureException("malformed signature");
        }

        byte[] expected = hmacSha256(timestampHeader, rawBody);
        if (!MessageDigest.isEqual(expected, provided)) {
            throw new InvalidWebhookSignatureException("signature mismatch");
        }
    }

    private byte[] hmacSha256(String timestamp, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(rawBody);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
