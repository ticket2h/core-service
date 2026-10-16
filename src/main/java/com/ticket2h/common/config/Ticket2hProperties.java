package com.ticket2h.common.config;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Toàn bộ cấu hình {@code ticket2h.*} trong application.yml. */
@Validated
@ConfigurationProperties("ticket2h")
public record Ticket2hProperties(
        @Valid @NotNull Jobs jobs,
        @Valid @NotNull OrderRules order,
        @Valid @NotNull Payment payment,
        @Valid @NotNull Outbox outbox,
        @Valid @NotNull TicketRules ticket,
        @Valid @NotNull Qr qr,
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull InternalApi internalApi,
        @Valid @NotNull IdentityApi identityApi) {

    public record Jobs(boolean enabled) {}

    public record OrderRules(
            @NotNull Duration holdDuration,
            @NotNull Duration expiryGrace,
            @NotNull Duration paymentExtension,
            @Positive int maxPaymentAttempts) {}

    public record Payment(@Valid @NotNull MockPay mockpay) {}

    public record MockPay(
            @NotNull URI baseUrl,
            @NotBlank String apiKey,
            @NotBlank String webhookSecret,
            @NotBlank String returnUrl,
            @NotBlank String notifyUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {}

    public record Outbox(@Valid @NotNull Relay relay, @NotNull Duration retention) {}

    public record Relay(boolean enabled, @NotNull Duration interval) {}

    public record TicketRules(@NotNull Duration transferCutoff) {}

    public record Qr(
            @NotBlank String activeKeyId,
            @NotBlank String privateKey,
            @NotEmpty Map<String, String> publicKeys) {}

    /**
     * Kiểm tra JWT do identity-service phát: chữ ký RS256 bằng public key lấy từ {@code jwkSetUri} (JWKS), đúng
     * {@code issuer}, còn hạn. core không giữ khoá ký nên không tự phát được token.
     */
    public record Jwt(@NotBlank String issuer, @NotNull URI jwkSetUri) {}

    /** API nội bộ của identity-service. {@code apiKey} là khoá của core, gửi trong header X-Internal-Api-Key. */
    public record IdentityApi(
            @NotNull URI baseUrl,
            @NotBlank @Size(min = 32, message = "phải dài ít nhất 32 ký tự") String apiKey,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {}

    /** Khoá của từng service được gọi {@code /internal/**}: tên service → khoá (tối thiểu 32 ký tự). */
    public record InternalApi(
            @NotEmpty Map<String, @NotBlank @Size(min = 32, message = "phải dài ít nhất 32 ký tự") String> clients) {}
}
