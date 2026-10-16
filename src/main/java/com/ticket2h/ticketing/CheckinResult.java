package com.ticket2h.ticketing;

public enum CheckinResult {
    OK,
    /** QR sai định dạng, sai chữ ký, khoá lạ, hoặc không có vé tương ứng. */
    INVALID_TOKEN,
    ALREADY_USED,
    /** QR cũ của vé đã chuyển nhượng (token_version không khớp). */
    TOKEN_REVOKED,
    VOIDED,
    /** Nhân viên không được phân công sự kiện của vé. Trả 403. */
    WRONG_EVENT_STAFF
}
