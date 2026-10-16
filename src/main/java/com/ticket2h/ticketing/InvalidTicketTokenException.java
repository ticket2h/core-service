package com.ticket2h.ticketing;

/** QR không hợp lệ. Check-in chuyển thành kết quả INVALID_TOKEN (200), không phải lỗi 500. */
public class InvalidTicketTokenException extends RuntimeException {

    public InvalidTicketTokenException(String reason) {
        super(reason);
    }
}
