package com.ticket2h.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;

import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;

/**
 * core chưa lấy được public key lần nào (vừa khởi động) mà identity-service không trả lời: không kiểm tra được
 * token, phải báo 502 ProblemDetail chứ không phải 500 mặc định, và không bảo khách là token sai (401).
 * Context riêng ({@code DirtiesContext} BEFORE_CLASS): decoder trong context dùng chung đã giữ sẵn public key.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class JwksUnavailableIT extends IntegrationTest {

    @Test
    void cannot_fetch_jwks_gives_502_problem_detail_and_recovers_when_identity_is_back() {
        String token = testData.token(testData.user());
        identity.jwksDown(true);

        Api.Response down = api.get("/me/orders", token);

        assertThat(down.status()).isEqualTo(502);
        assertThat(down.code()).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(down.header("Content-Type")).startsWith("application/problem+json");

        identity.jwksDown(false);
        assertThat(api.get("/me/orders", token).status()).isEqualTo(200);
    }
}
