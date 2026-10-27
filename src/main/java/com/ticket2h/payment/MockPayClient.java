package com.ticket2h.payment;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.ticket2h.common.config.Ticket2hProperties;

/**
 * Gọi API của MockPay. Có timeout (kết nối 2 giây, đọc 5 giây). KHÔNG gọi bên trong transaction:
 * gọi ngoài mạng lúc đang giữ khoá database làm webhook và job hết hạn phải chờ theo.
 */
@Component
public class MockPayClient {

    private final RestClient rest;

    MockPayClient(Ticket2hProperties properties) {
        Ticket2hProperties.MockPay config = properties.payment().mockpay();
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(config.readTimeout());
        this.rest = RestClient.builder()
                .baseUrl(config.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader("X-Api-Key", config.apiKey())
                .build();
    }

    public CreateSessionResponse createPayment(CreateSessionRequest request) {
        return call("tạo phiên thanh toán", () -> rest.post().uri("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(CreateSessionResponse.class));
    }

    public RefundResponse refund(RefundRequest request) {
        return call("hoàn tiền", () -> rest.post().uri("/api/v1/refunds")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(RefundResponse.class));
    }

    /**
     * {@code GET /api/v1/payments/by-ref/{merchantRef}}: trạng thái phiên ở cổng, dùng cho đối soát (ADR-022). Cổng
     * không có phiên nào với {@code merchantRef} này (tạo phiên lỗi trước khi tới cổng) → rỗng.
     */
    public Optional<GatewayPayment> paymentByMerchantRef(String merchantRef) {
        try {
            return Optional.of(call("tra trạng thái thanh toán", () -> rest.get()
                    .uri("/api/v1/payments/by-ref/{merchantRef}", merchantRef)
                    .retrieve()
                    .body(GatewayPayment.class)));
        } catch (MockPayException e) {
            if (e.getCause() instanceof HttpClientErrorException.NotFound) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private static <T> T call(String action, java.util.function.Supplier<T> request) {
        T response;
        try {
            response = request.get();
        } catch (RestClientResponseException e) {
            throw new MockPayException("MockPay " + action + " trả HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new MockPayException("Không gọi được MockPay (" + action + "): " + e.getMessage(), e);
        }
        if (response == null) {
            throw new MockPayException("MockPay " + action + " trả body rỗng", null);
        }
        return response;
    }

    public record CreateSessionRequest(String merchantRef, BigDecimal amount, String currency, String description,
            String returnUrl, String notifyUrl, long expiresInSeconds) {}

    public record CreateSessionResponse(String gatewayPaymentId, String paymentUrl, Instant expiresAt) {}

    public record RefundRequest(String gatewayTransactionId, BigDecimal amount, String idempotencyKey) {}

    public record RefundResponse(String refundId, String status) {}

    /**
     * {@code status}: PENDING, SUCCEEDED, FAILED, CANCELLED, EXPIRED. Để dạng chuỗi: cổng thêm trạng thái mới thì core
     * vẫn đọc được (coi như chưa có kết quả).
     */
    public record GatewayPayment(String status, String gatewayPaymentId, String gatewayTransactionId, BigDecimal amount,
            String currency) {

        static final String PENDING = "PENDING";
        static final String SUCCEEDED = "SUCCEEDED";
        static final String FAILED = "FAILED";
        static final String CANCELLED = "CANCELLED";
        static final String EXPIRED = "EXPIRED";
    }

    public static class MockPayException extends RuntimeException {
        public MockPayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
