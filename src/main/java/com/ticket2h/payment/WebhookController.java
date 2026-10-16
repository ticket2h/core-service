package com.ticket2h.payment;

import java.io.IOException;
import java.io.InputStream;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;

/** Không cần JWT ({@code permitAll}); bảo vệ duy nhất là chữ ký. */
@RestController
class WebhookController {

    static final int MAX_BODY_BYTES = 64 * 1024;

    private final WebhookIngestion ingestion;

    WebhookController(WebhookIngestion ingestion) {
        this.ingestion = ingestion;
    }

    /**
     * Đọc body thô dạng byte (chữ ký tính trên đúng các byte này). Đọc tối đa 64KB: không dùng
     * {@code @RequestBody byte[]} vì nó đọc hết body vào bộ nhớ trước khi ta kịp kiểm tra kích thước.
     */
    @PostMapping(path = "/webhooks/payments/mockpay", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> receive(
            @RequestHeader(value = "X-MockPay-Event-Id", required = false) String eventId,
            @RequestHeader(value = "X-MockPay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-MockPay-Signature", required = false) String signature,
            HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        byte[] body;
        try (InputStream in = request.getInputStream()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw tooLarge();
        }
        ingestion.handle(eventId, timestamp, signature, body);
        return ResponseEntity.ok().build();
    }

    private static BusinessException tooLarge() {
        return new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "Webhook tối đa 64KB");
    }
}
