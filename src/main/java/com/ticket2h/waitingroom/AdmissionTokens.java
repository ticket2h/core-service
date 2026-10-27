package com.ticket2h.waitingroom;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.ticket2h.common.config.Ticket2hProperties;

/**
 * Vé vào cửa của phòng chờ (ADR-027): {@code v1.<sự kiện>.<người dùng>.<hết hạn>.<HMAC-SHA256>}. Instance nào cũng
 * kiểm tra được mà không hỏi Redis hay database: lúc mở bán, API đặt vé không thêm một lần gọi mạng nào. Gắn với một
 * người và một sự kiện: đưa vé cho người khác, hay dùng cho sự kiện khác, đều bị từ chối.
 */
@Component
class AdmissionTokens {

    private static final String VERSION = "v1";
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final SecretKeySpec key;

    AdmissionTokens(Ticket2hProperties properties) {
        this.key = new SecretKeySpec(properties.waitingRoom().secret().getBytes(StandardCharsets.UTF_8), ALGORITHM);
    }

    String issue(UUID eventId, UUID userId, Instant expiresAt) {
        String payload = VERSION + "." + eventId + "." + userId + "." + expiresAt.getEpochSecond();
        return payload + "." + ENCODER.encodeToString(mac(payload));
    }

    /** Hết hạn của vé nếu vé đúng chữ ký, đúng sự kiện, đúng người và chưa hết hạn. */
    Optional<Instant> verify(String token, UUID eventId, UUID userId, Instant now) {
        int lastDot = token.lastIndexOf('.');
        if (lastDot < 0) {
            return Optional.empty();
        }
        String payload = token.substring(0, lastDot);
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(token.substring(lastDot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        // So sánh thời gian hằng: không lộ chữ ký đúng tới byte thứ mấy
        if (!MessageDigest.isEqual(mac(payload), signature)) {
            return Optional.empty();
        }
        String[] parts = payload.split("\\.");
        if (parts.length != 4 || !parts[0].equals(VERSION) || !parts[1].equals(eventId.toString())
                || !parts[2].equals(userId.toString())) {
            return Optional.empty();
        }
        Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[3]));
        return now.isBefore(expiresAt) ? Optional.of(expiresAt) : Optional.empty();
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);   // Mac không an toàn khi dùng chung giữa các luồng
            mac.init(key);
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
