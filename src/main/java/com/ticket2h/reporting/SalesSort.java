package com.ticket2h.reporting;

/**
 * Cột người dùng được chọn để sắp xếp báo cáo. Chỉ nhận đúng các giá trị này (Spring trả 400 với giá trị khác), và mỗi
 * giá trị được dịch sang một đoạn SQL viết sẵn trong {@link SalesReportStore}: chuỗi người dùng gửi lên không bao giờ
 * được ghép vào câu SQL, nên không có SQL injection qua tham số sắp xếp.
 */
public enum SalesSort {
    /** Theo khoá của nhóm: ngày, tên loại vé, tên kênh. */
    KEY,
    ORDERS,
    TICKETS,
    REVENUE
}
