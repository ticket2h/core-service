package com.ticket2h.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.common.security.AuthUser;

@RestController
class PaymentController {

    private final PaymentInitiation initiation;

    PaymentController(PaymentInitiation initiation) {
        this.initiation = initiation;
    }

    /** 201 khi tạo phiên mới, 200 khi trả lại phiên đang dùng được. */
    @PostMapping("/orders/{orderId}/payments")
    ResponseEntity<PaymentResponse> initiate(AuthUser caller, @PathVariable UUID orderId) {
        PaymentInitiation.Initiated result = initiation.initiate(caller.id(), orderId);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(PaymentResponse.of(result.payment()));
    }

    /**
     * Trang dự phòng cho {@code ticket2h.payment.mockpay.return-url}: mặc định MockPay chuyển trình duyệt về trang
     * {@code /payments/return} của web-app (kèm {@code orderId}), trang này chỉ dùng khi ai đó trỏ return-url về
     * thẳng core (chạy core không có web-app). CHỈ hiển thị, không đổi trạng thái: tham số {@code status} trên URL
     * không có chữ ký, ai cũng sửa được. Kết quả thật đến qua webhook.
     */
    @GetMapping(path = "/payments/return", produces = MediaType.TEXT_HTML_VALUE)
    String returnPage() {
        return """
                <!doctype html>
                <html lang="vi"><head><meta charset="utf-8"><title>Ticket2h</title></head>
                <body><h1>Đang xác nhận thanh toán…</h1>
                <p>Kết quả sẽ được cập nhật vào đơn hàng trong giây lát. Bạn có thể đóng trang này.</p>
                </body></html>
                """;
    }

    record PaymentResponse(UUID paymentId, UUID orderId, PaymentStatus status, BigDecimal amount, String currency,
            String gatewayPaymentId, String paymentUrl, Instant expiresAt) {
        static PaymentResponse of(Payment p) {
            return new PaymentResponse(p.getId(), p.getOrderId(), p.getStatus(), p.getAmount(), p.getCurrency(),
                    p.getGatewayPaymentId(), p.getPaymentUrl(), p.getExpiresAt());
        }
    }
}
