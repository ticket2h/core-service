package com.ticket2h.ordering;

/** Lỗi lập trình: code cố đổi đơn sang trạng thái không hợp lệ. Trả 500, không phải lỗi nghiệp vụ. */
public class IllegalOrderTransitionException extends IllegalStateException {

    public IllegalOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("Không thể chuyển đơn từ " + from + " sang " + to);
    }
}
