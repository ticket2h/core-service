package com.ticket2h.payment;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.payment.MockPayClient.CreateSessionRequest;
import com.ticket2h.payment.MockPayClient.CreateSessionResponse;
import com.ticket2h.payment.MockPayClient.MockPayException;

/**
 * {@code POST /orders/{id}/payments}. KHÔNG có {@code @Transactional}: transaction 1 tạo payment,
 * gọi MockPay ngoài transaction, transaction 2 lưu kết quả.
 *
 * <p>Ca khó: MockPay đã tạo phiên nhưng ta timeout và đánh dấu FAILED. Khách vẫn có thể trả trên phiên
 * đó; webhook {@code payment.succeeded} cho payment FAILED vẫn được nhận (FAILED → SUCCEEDED).
 */
@Service
public class PaymentInitiation {

    private static final Logger log = LoggerFactory.getLogger(PaymentInitiation.class);

    private final PaymentInitiationSteps steps;
    private final MockPayClient mockPay;
    private final Ticket2hProperties properties;
    private final Clock clock;

    PaymentInitiation(PaymentInitiationSteps steps, MockPayClient mockPay, Ticket2hProperties properties,
            Clock clock) {
        this.steps = steps;
        this.mockPay = mockPay;
        this.properties = properties;
        this.clock = clock;
    }

    public Initiated initiate(UUID userId, UUID orderId) {
        PaymentInitiationSteps.Prepared prepared = steps.prepare(userId, orderId);
        Payment payment = prepared.payment();
        if (!prepared.needsGatewayCall()) {
            return new Initiated(payment, prepared.created());
        }

        Ticket2hProperties.MockPay config = properties.payment().mockpay();
        long expiresInSeconds = Math.max(1, Duration.between(clock.instant(), payment.getExpiresAt()).toSeconds());
        CreateSessionResponse session;
        try {
            // merchantRef = payment.id: gọi lại cùng merchantRef thì MockPay trả lại phiên cũ
            session = mockPay.createPayment(new CreateSessionRequest(payment.getId().toString(),
                    payment.getAmount(), payment.getCurrency(), "Ticket2h order " + orderId,
                    returnUrlFor(config.returnUrl(), payment.getOrderId()), config.notifyUrl(), expiresInSeconds));
        } catch (MockPayException e) {
            log.warn("Tạo phiên MockPay thất bại cho payment {}: {}", payment.getId(), e.getMessage());
            steps.recordGatewayFailure(payment.getId(), e.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                    "Cổng thanh toán không phản hồi, vui lòng thử lại");
        }
        Payment saved = steps.recordSession(payment.getId(), session.gatewayPaymentId(), session.paymentUrl());
        return new Initiated(saved, prepared.created());
    }

    /**
     * return-url đã cấu hình kèm {@code orderId}: MockPay chuyển trình duyệt về
     * {@code returnUrl?orderId=...&merchantRef=<paymentId>&status=...}, nên web-app biết hiện đơn nào
     * (merchantRef là id của payment, không phải của đơn). Query có sẵn trong URL cấu hình được giữ nguyên.
     *
     * <p>Cùng một payment luôn ra cùng một URL, nên gọi lại MockPay với cùng merchantRef vẫn gửi đúng như lần đầu
     * (MockPay trả lại phiên cũ, không đổi returnUrl đã lưu). {@code build(true)}: URL cấu hình phải ở dạng đã
     * encode (MockPay cũng đọc nó như vậy khi chuyển hướng); UUID không có ký tự cần encode.
     */
    static String returnUrlFor(String configuredReturnUrl, UUID orderId) {
        return UriComponentsBuilder.fromUriString(configuredReturnUrl)
                .queryParam("orderId", orderId)
                .build(true)
                .toUriString();
    }

    public record Initiated(Payment payment, boolean created) {}
}
