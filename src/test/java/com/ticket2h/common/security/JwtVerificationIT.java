package com.ticket2h.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ticket2h.support.FakeIdentity;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData;
import com.ticket2h.support.TestKeys;

/**
 * core chỉ tin JWT do identity-service ký (RS256, public key lấy từ JWKS). Ai không có private key của identity
 * thì không tạo được token mà core chấp nhận (ADR-013 trong infra/docs/adr/).
 */
class JwtVerificationIT extends IntegrationTest {

    @Test
    void token_signed_by_identity_key_is_accepted() {
        assertThat(api.get("/me/orders", testData.token(testData.user())).status()).isEqualTo(200);
    }

    @Test
    void token_signed_by_another_rsa_key_is_rejected_even_with_the_same_kid() {
        UUID user = testData.user();

        String sameKid = TestData.sign(TestKeys.rsa(), FakeIdentity.KEY_ID, claims(user, "ticket2h"));
        String unknownKid = TestData.sign(TestKeys.rsa(), "attacker-k1", claims(user, "ticket2h"));

        assertThat(api.get("/me/orders", sameKid).status()).isEqualTo(401);
        assertThat(api.get("/me/orders", unknownKid).status()).isEqualTo(401);
    }

    /** Kiểu tấn công "đổi thuật toán": ký HS256 rồi mong server nhầm. core chỉ chấp nhận RS256. */
    @Test
    void hs256_token_is_rejected() {
        var secret = new SecretKeySpec("a-secret-that-is-at-least-32-bytes-long!".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");
        String hs256 = new NimbusJwtEncoder(new ImmutableSecret<>(secret))
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
                        claims(testData.user(), "ticket2h")))
                .getTokenValue();

        assertThat(api.get("/me/orders", hs256).status()).isEqualTo(401);
    }

    @Test
    void token_from_another_issuer_is_rejected() {
        String token = TestData.sign(TestKeys.JWT, FakeIdentity.KEY_ID, claims(testData.user(), "someone-else"));

        assertThat(api.get("/me/orders", token).status()).isEqualTo(401);
    }

    /** Ban tổ chức của người gọi lấy từ claim organizerId: không có claim thì không làm gì được ở /organizer. */
    @Test
    void organizer_token_without_organizer_claim_gets_403() {
        UUID orphan = testData.userWithEmail("orphan@test.local", Role.ORGANIZER, null);
        UUID organizer = testData.organizer().userId();

        assertThat(api.get("/organizer/venues", testData.token(orphan)).status()).isEqualTo(403);
        assertThat(api.get("/organizer/venues", testData.token(organizer)).status()).isEqualTo(200);
    }

    private JwtClaimsSet claims(UUID user, String issuer) {
        Instant now = clock.instant();
        return JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(user.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .claim(JwtClaims.ROLE, Role.CUSTOMER.name())
                .build();
    }
}
