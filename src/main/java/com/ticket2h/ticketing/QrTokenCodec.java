package com.ticket2h.ticketing;

import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Base64;
import java.util.Map;

/**
 * Token QR: {@code TR1.<keyId>.<payload base64url>.<chữ ký base64url>}, ký Ed25519.
 *
 * <ul>
 *   <li>Máy soát vé chỉ cần public key để phát hiện QR giả, kể cả khi mất mạng.</li>
 *   <li>{@code keyId}: thay khoá định kỳ mà vé ký bằng khoá cũ vẫn kiểm tra được.</li>
 *   <li>Chữ ký Ed25519 tất định, nên token tính lại được từ dữ liệu vé; không lưu token trong DB.</li>
 * </ul>
 *
 * <p>Bẫy base64: chữ ký 64 byte ra 86 ký tự, ký tự cuối chỉ dùng 2 bit. Bộ giải mã của Java bỏ qua
 * 4 bit thừa, nên đổi ký tự cuối vẫn có thể ra đúng chữ ký cũ. Vì vậy chỉ nhận dạng base64 chuẩn:
 * giải mã xong mã hoá lại, khác chuỗi gốc thì từ chối.
 */
public class QrTokenCodec {

    private static final String PREFIX = "TR1";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final String activeKeyId;
    private final PrivateKey signingKey;
    private final Map<String, PublicKey> verificationKeys;

    public QrTokenCodec(String activeKeyId, PrivateKey signingKey, Map<String, PublicKey> verificationKeys) {
        if (!verificationKeys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("Thiếu public key cho khoá đang dùng " + activeKeyId);
        }
        this.activeKeyId = activeKeyId;
        this.signingKey = signingKey;
        this.verificationKeys = Map.copyOf(verificationKeys);
    }

    public String encode(TicketClaims claims) {
        byte[] payload = claims.toBytes();
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(signingKey);
            signer.update(payload);
            return String.join(".", PREFIX, activeKeyId,
                    ENCODER.encodeToString(payload), ENCODER.encodeToString(signer.sign()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @throws InvalidTicketTokenException sai định dạng, khoá lạ, sai chữ ký */
    public TicketClaims decode(String token) {
        if (token == null) {
            throw new InvalidTicketTokenException("format");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            throw new InvalidTicketTokenException("format");
        }
        PublicKey key = verificationKeys.get(parts[1]);
        if (key == null) {
            throw new InvalidTicketTokenException("unknown key");
        }
        byte[] payload = decodeCanonical(parts[2]);
        byte[] signature = decodeCanonical(parts[3]);
        if (payload.length != TicketClaims.LENGTH) {
            throw new InvalidTicketTokenException("payload length");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            if (!verifier.verify(signature)) {
                throw new InvalidTicketTokenException("signature");
            }
        } catch (SignatureException e) {
            throw new InvalidTicketTokenException("signature");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return TicketClaims.fromBytes(payload);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    private static byte[] decodeCanonical(String part) {
        byte[] bytes;
        try {
            bytes = DECODER.decode(part);
        } catch (IllegalArgumentException e) {
            throw new InvalidTicketTokenException("base64");
        }
        if (!ENCODER.encodeToString(bytes).equals(part)) {
            throw new InvalidTicketTokenException("non-canonical base64");
        }
        return bytes;
    }
}
