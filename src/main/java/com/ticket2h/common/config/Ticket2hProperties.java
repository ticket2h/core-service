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
import org.springframework.util.unit.DataSize;
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
        @Valid @NotNull IdentityApi identityApi,
        @Valid @NotNull SeatMap seatMap,
        @Valid @NotNull Cache cache,
        @Valid @NotNull RateLimit rateLimit,
        @Valid @NotNull WaitingRoom waitingRoom,
        @NotNull SeatGateMode seatGate) {

    /** Lớp lọc trước khi giữ ghế trong database (ADR-028). */
    public enum SeatGateMode { DATABASE, REDIS }

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

    /**
     * Cache trong bộ nhớ của từng instance, làm mới ở nền (ADR-025). {@code eventTtl}, {@code seatMapTtl}: bản trong
     * cache cũ quá ngần này thì được tải lại ở nền (0: không cache). Mỗi instance một cache, không báo nhau khi dữ liệu
     * đổi. {@code maxStale}: tải lại mãi không được thì bỏ bản cũ sau ngần này. {@code seatMapMaxSize}: tổng kích thước
     * JSON các sơ đồ ghế giữ trong cache (một sơ đồ 120.000 ghế khoảng 20MB).
     */
    public record Cache(@NotNull Duration eventTtl, @NotNull Duration seatMapTtl, @NotNull Duration maxStale,
            @NotNull DataSize seatMapMaxSize) {}

    /**
     * Phòng chờ (ADR-027). {@code secret}: khoá HMAC ký vé vào cửa, mọi instance dùng chung. {@code opensBeforeSale}:
     * vào xếp hàng được từ trước giờ mở bán ngần này (người đến trước giờ mở bán được xếp ngẫu nhiên).
     * {@code admissionTtl}: vé vào cửa dùng được trong ngần này. {@code staleAfter}: không hỏi thứ tự quá lâu (đóng
     * tab) thì bị bỏ khỏi hàng; phải lớn hơn khoảng hỏi dài nhất của web (15 giây).
     */
    public record WaitingRoom(
            @NotBlank @Size(min = 32) String secret,
            @NotNull Duration opensBeforeSale,
            @NotNull Duration admissionTtl,
            @NotNull Duration staleAfter) {}

    /** Giới hạn request bằng token bucket trên Redis (ADR-026): theo IP mọi request, theo người dùng vài API. */
    public record RateLimit(
            boolean enabled,
            @Valid @NotNull Bucket perIp,
            @Valid @NotNull Bucket orders,
            @Valid @NotNull Bucket waitingRoom) {

        /** Tối đa {@code capacity} request dồn một lúc, trung bình {@code refillPerSecond} request mỗi giây. */
        public record Bucket(@Positive int capacity, @Positive double refillPerSecond) {}
    }

    /** Sơ đồ ghế cập nhật trực tiếp qua SSE (ADR-024). {@code maxStreams}: số kết nối tối đa mỗi instance. */
    public record SeatMap(
            @NotNull Duration flushInterval,
            @NotNull Duration streamTimeout,
            @NotNull Duration heartbeat,
            @Positive int maxStreams) {}

    /** Khoá của từng service được gọi {@code /internal/**}: tên service → khoá (tối thiểu 32 ký tự). */
    public record InternalApi(
            @NotEmpty Map<String, @NotBlank @Size(min = 32, message = "phải dài ít nhất 32 ký tự") String> clients) {}
}
