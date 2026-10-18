package com.ticket2h.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;

/** Web sinh kiểu TypeScript từ tài liệu này, nên nó phải mở công khai và có các API web dùng. */
class OpenApiDocsIT extends IntegrationTest {

    @Test
    void openapi_document_is_public_and_lists_the_event_api() {
        Api.Response docs = api.get("/v3/api-docs", null);

        assertThat(docs.status()).isEqualTo(200);
        assertThat(docs.json().get("paths").has("/events")).isTrue();
        assertThat(docs.json().get("paths").has("/events/{eventId}")).isTrue();
    }

    /** AuthUser điền từ JWT, không được hiện thành tham số query mà web phải gửi. */
    @Test
    void authenticated_caller_is_not_documented_as_a_parameter() {
        Api.Response docs = api.get("/v3/api-docs", null);

        assertThat(docs.body()).doesNotContain("\"caller\"");
    }
}
