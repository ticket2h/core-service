package com.ticket2h.ordering;

/**
 * Kênh bán của đơn, client khai báo khi tạo đơn (không gửi thì là {@link #WEB}). Chỉ dùng cho báo cáo doanh thu,
 * không ảnh hưởng giá hay quyền mua. Thêm kênh mới phải thêm cả vào ràng buộc {@code orders_sales_channel_check}.
 */
public enum SalesChannel {
    WEB, MOBILE_APP
}
