package com.ticket2h.audit;

/**
 * Thao tác được ghi vào lịch sử (ADR-023): thao tác nhạy cảm của ban tổ chức và việc hệ thống tự làm với tiền. Thêm
 * thao tác mới: thêm hằng ở đây (kèm loại đối tượng bị tác động) và nhãn tiếng Việt ở trang quản trị của web-app.
 */
public enum AuditAction {
    VENUE_CREATED(AuditTarget.VENUE),
    EVENT_CREATED(AuditTarget.EVENT),
    /** {@code details.changes}: mỗi trường đổi có {@code from}, {@code to}. */
    EVENT_UPDATED(AuditTarget.EVENT),
    EVENT_PUBLISHED(AuditTarget.EVENT),
    /** Bật, tắt phòng chờ hoặc đổi số người vào mỗi giây (ADR-027): {@code from}, {@code to} ({@code null} là tắt). */
    WAITING_ROOM_CHANGED(AuditTarget.EVENT),
    TICKET_TYPE_CREATED(AuditTarget.TICKET_TYPE),
    /** Đổi giá: chỉ áp dụng cho đơn tạo sau đó; đơn cũ giữ giá lúc mua. */
    TICKET_TYPE_PRICE_CHANGED(AuditTarget.TICKET_TYPE),
    SEATS_ADDED(AuditTarget.EVENT),
    SEATS_IMPORTED(AuditTarget.EVENT),
    STAFF_ASSIGNED(AuditTarget.USER),
    STAFF_REMOVED(AuditTarget.USER),
    /** Tải danh sách khách (có họ tên, email): truy cập dữ liệu cá nhân. */
    ATTENDEES_EXPORTED(AuditTarget.EVENT),
    CHECKINS_EXPORTED(AuditTarget.EVENT),
    PAYMENT_REFUND_REQUESTED(AuditTarget.PAYMENT),
    PAYMENT_REFUNDED(AuditTarget.PAYMENT),
    PAYMENT_AMOUNT_MISMATCH(AuditTarget.PAYMENT),
    /** Đối soát với cổng (ADR-022) đã đổi trạng thái payment vì webhook không đến. */
    PAYMENT_RECONCILED(AuditTarget.PAYMENT);

    private final AuditTarget target;

    AuditAction(AuditTarget target) {
        this.target = target;
    }

    public AuditTarget target() {
        return target;
    }

    public enum AuditTarget {
        VENUE, EVENT, TICKET_TYPE, USER, PAYMENT
    }
}
