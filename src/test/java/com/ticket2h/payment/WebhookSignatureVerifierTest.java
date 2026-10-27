package com.ticket2h.payment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.support.TestData;

/** Bước 10.1. */
class WebhookSignatureVerifierTest {

    private static final String SECRET = "whsec_test";
    private static final Instant NOW = Instant.parse("2026-11-01T12:00:00Z");
    private static final byte[] BODY = """
            {"eventId":"evt_1","type":"payment.succeeded","merchantRef":"x","amount":4600000,"currency":"VND"}"""
            .getBytes(StandardCharsets.UTF_8);

    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(properties(SECRET),
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final String ts = String.valueOf(NOW.getEpochSecond());

    @Test
    void valid_signature_passes() {
        assertThatCode(() -> verifier.verify(ts, TestData.sign(SECRET, ts, BODY), BODY)).doesNotThrowAnyException();
    }

    @Test
    void known_vector_computed_with_openssl() {
        // printf '%s' '1793534400.{"a":1}' | openssl dgst -sha256 -hmac whsec_test
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        WebhookSignatureVerifier atVectorTime = new WebhookSignatureVerifier(properties(SECRET),
                Clock.fixed(Instant.ofEpochSecond(1793534400L), ZoneOffset.UTC));
        assertThatCode(() -> atVectorTime.verify("1793534400",
                "9a56f65e7f2a13b5f855b9d791bcf92ff45523e349866362144e22b780c65a44", body))
                .doesNotThrowAnyException();
    }

    @Test
    void one_changed_byte_in_body_is_rejected() {
        String signature = TestData.sign(SECRET, ts, BODY);
        byte[] tampered = BODY.clone();
        tampered[tampered.length - 5] = (byte) '9';

        assertThatThrownBy(() -> verifier.verify(ts, signature, tampered))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void signed_with_another_secret_is_rejected() {
        assertThatThrownBy(() -> verifier.verify(ts, TestData.sign("other", ts, BODY), BODY))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void timestamp_six_minutes_old_or_ahead_is_rejected() {
        String old = String.valueOf(NOW.minus(Duration.ofMinutes(6)).getEpochSecond());
        String future = String.valueOf(NOW.plus(Duration.ofMinutes(6)).getEpochSecond());

        assertThatThrownBy(() -> verifier.verify(old, TestData.sign(SECRET, old, BODY), BODY))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(future, TestData.sign(SECRET, future, BODY), BODY))
                .isInstanceOf(InvalidWebhookSignatureException.class);
    }

    @Test
    void missing_headers_or_garbage_are_rejected_without_500() {
        String signature = TestData.sign(SECRET, ts, BODY);
        assertThatThrownBy(() -> verifier.verify(null, signature, BODY)).isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(ts, null, BODY)).isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(ts, "not-hex!", BODY)).isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify("abc", signature, BODY)).isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify("99999999999999999", signature, BODY))
                .isInstanceOf(InvalidWebhookSignatureException.class);
        assertThatThrownBy(() -> verifier.verify(ts, "ab", BODY)).isInstanceOf(InvalidWebhookSignatureException.class);
    }

    private static Ticket2hProperties properties(String secret) {
        var mockPay = new Ticket2hProperties.MockPay(URI.create("http://localhost"), "key", secret, "r", "n",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        return new Ticket2hProperties(null, null, new Ticket2hProperties.Payment(mockPay), null, null, null, null, null,
                null, null, null, null, null, null);
    }
}
