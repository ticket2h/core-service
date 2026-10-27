package com.ticket2h.common.error;

import java.net.URI;
import java.util.Locale;

import org.springframework.http.HttpStatus;

/** Mã lỗi trả trong trường {@code code} của ProblemDetail. Xem bảng mã lỗi trong docs/api.md. */
public enum ErrorCode {

    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
    IDEMPOTENCY_KEY_MISSING(HttpStatus.BAD_REQUEST, "Thiếu header Idempotency-Key"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Chưa đăng nhập hoặc token không hợp lệ"),
    INVALID_WEBHOOK_SIGNATURE(HttpStatus.UNAUTHORIZED, "Chữ ký webhook không hợp lệ"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Không có quyền"),
    ADMISSION_REQUIRED(HttpStatus.FORBIDDEN, "Cần xếp hàng trong phòng chờ trước khi đặt vé"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy"),
    RECIPIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy người nhận"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Phương thức không được hỗ trợ"),
    EVENT_NOT_EDITABLE(HttpStatus.CONFLICT, "Sự kiện không còn sửa được phần này"),
    EVENT_NOT_PUBLISHABLE(HttpStatus.CONFLICT, "Sự kiện chưa đủ điều kiện công bố"),
    SALE_NOT_OPEN(HttpStatus.CONFLICT, "Ngoài giờ mở bán"),
    WAITING_ROOM_NOT_OPEN(HttpStatus.CONFLICT, "Phòng chờ chưa mở hoặc đã đóng"),
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "Ghế không còn trống"),
    SOLD_OUT(HttpStatus.CONFLICT, "Không đủ vé"),
    PURCHASE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "Vượt số vé tối đa mỗi người"),
    ORDER_NOT_PAYABLE(HttpStatus.CONFLICT, "Đơn hàng không thể thanh toán"),
    ORDER_NOT_CANCELLABLE(HttpStatus.CONFLICT, "Đơn hàng không thể huỷ"),
    PAYMENT_ATTEMPTS_EXCEEDED(HttpStatus.CONFLICT, "Đã tạo quá nhiều phiên thanh toán"),
    TICKET_NOT_TRANSFERABLE(HttpStatus.CONFLICT, "Vé không thể chuyển nhượng"),
    TICKET_NOT_VALID(HttpStatus.CONFLICT, "Vé không còn hiệu lực"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Nội dung request quá lớn"),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "Quá nhiều request"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_CONTENT, "Idempotency-Key đã dùng cho request khác"),
    SEAT_IMPORT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "File ghế có lỗi"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống"),
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Cổng thanh toán không phản hồi"),
    IDENTITY_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "Dịch vụ tài khoản không phản hồi"),
    WAITING_ROOM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Phòng chờ tạm thời không hoạt động"),
    SEAT_MAP_STREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,
            "Quá nhiều người đang xem sơ đồ ghế trực tiếp, hãy tải lại định kỳ");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    /** Ví dụ SEAT_UNAVAILABLE → https://ticket2h.dev/errors/seat-unavailable */
    public URI type() {
        return URI.create("https://ticket2h.dev/errors/" + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
