package com.ticket2h.payment;

/** Kết quả xử lý một webhook, ghi vào {@code payment_webhook_logs.result}. */
enum WebhookOutcome {
    /** Đơn chuyển PAID. */
    PAID,
    /** Payment đã được ghi nhận trước đó (webhook gửi lại hoặc song song). */
    DUPLICATE,
    /** Tiền đã trừ nhưng đơn không nhận được nữa: payment chuyển REFUND_PENDING. */
    REFUND_REQUESTED,
    /** Số tiền hoặc tiền tệ không khớp: cần người kiểm tra. */
    AMOUNT_MISMATCH,
    /** Không có payment ứng với merchantRef. */
    UNKNOWN_PAYMENT,
    /** {@code payment.failed}: payment chuyển FAILED. */
    FAILED_RECORDED,
    /** Event không làm thay đổi gì (loại lạ, failed cho payment đã kết thúc). */
    IGNORED
}
