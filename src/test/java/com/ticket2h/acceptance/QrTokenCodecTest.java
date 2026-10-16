package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.support.TestKeys;
import com.ticket2h.ticketing.InvalidTicketTokenException;
import com.ticket2h.ticketing.QrTokenCodec;
import com.ticket2h.ticketing.TicketClaims;

/** Bước 16: QR ký Ed25519. */
class QrTokenCodecTest {

    private static final String BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    private final KeyPair k1 = TestKeys.generate();
    private final KeyPair k2 = TestKeys.generate();
    private final QrTokenCodec codec = new QrTokenCodec("k1", k1.getPrivate(), Map.of("k1", k1.getPublic()));
    private final TicketClaims claims = new TicketClaims(UUID.randomUUID(), UUID.randomUUID(), 3);

    @Test
    void encode_then_decode_gives_the_same_claims() {
        String token = codec.encode(claims);

        assertThat(token).startsWith("TR1.k1.").hasSizeLessThan(150);
        assertThat(codec.decode(token)).isEqualTo(claims);
    }

    @Test
    void signature_is_deterministic_so_token_need_not_be_stored() {
        assertThat(codec.encode(claims)).isEqualTo(codec.encode(claims));
    }

    @Test
    void changing_one_character_in_the_middle_of_the_payload_is_rejected() {
        String token = codec.encode(claims);
        int middle = "TR1.k1.".length() + 20;
        String tampered = replaceAt(token, middle, token.charAt(middle) == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> codec.decode(tampered)).isInstanceOf(InvalidTicketTokenException.class);
    }

    /** Kể cả ký tự cuối: base64 có bit thừa, giải mã lỏng thì đổi ký tự cuối vẫn ra đúng chữ ký cũ. */
    @Test
    void changing_any_character_to_any_other_base64url_character_is_always_rejected() {
        String token = codec.encode(claims);
        int checked = 0;
        for (int i = 0; i < token.length(); i++) {
            for (char c : BASE64URL.toCharArray()) {
                if (c == token.charAt(i)) {
                    continue;
                }
                String tampered = replaceAt(token, i, c);
                assertThatThrownBy(() -> codec.decode(tampered))
                        .as("vị trí %d đổi thành %s", i, c)
                        .isInstanceOf(InvalidTicketTokenException.class);
                checked++;
            }
        }
        assertThat(checked).isGreaterThan(token.length() * 60);
    }

    @Test
    void token_signed_with_another_key_under_our_key_id_is_rejected() {
        QrTokenCodec forger = new QrTokenCodec("k1", k2.getPrivate(), Map.of("k1", k2.getPublic()));

        assertThatThrownBy(() -> codec.decode(forger.encode(claims))).isInstanceOf(InvalidTicketTokenException.class);
    }

    @Test
    void malformed_tokens_are_rejected_without_other_exceptions() {
        String token = codec.encode(claims);
        String[] parts = token.split("\\.");
        for (String bad : new String[] {
                "", ".", "TR1", "TR1.k1", "TR1.k1.x", "TR2." + token.substring(4),
                "TR1.k9." + parts[2] + "." + parts[3],
                "TR1.k1." + parts[2] + "." + parts[3] + ".extra",
                "TR1.k1." + parts[2] + ".",
                "TR1.k1.!!!." + parts[3],
                "TR1.k1." + parts[2].substring(0, 10) + "." + parts[3]}) {
            assertThatThrownBy(() -> codec.decode(bad)).as(bad).isInstanceOf(InvalidTicketTokenException.class);
        }
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(InvalidTicketTokenException.class);
    }

    @Test
    void tokens_signed_with_k1_stay_valid_after_rotating_to_k2() {
        String oldToken = codec.encode(claims);
        QrTokenCodec rotated = new QrTokenCodec("k2", k2.getPrivate(),
                Map.of("k1", k1.getPublic(), "k2", k2.getPublic()));

        assertThat(rotated.decode(oldToken)).isEqualTo(claims);
        assertThat(rotated.encode(claims)).startsWith("TR1.k2.");
        assertThat(rotated.decode(rotated.encode(claims))).isEqualTo(claims);
    }

    private static String replaceAt(String s, int index, char c) {
        return s.substring(0, index) + c + s.substring(index + 1);
    }
}
