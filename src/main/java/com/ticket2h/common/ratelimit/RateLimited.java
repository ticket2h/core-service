package com.ticket2h.common.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Giới hạn thêm theo người dùng cho một endpoint (ngoài giới hạn chung theo IP), ADR-026. Chưa đăng nhập thì tính
 * theo IP. Mức giới hạn cấu hình ở {@code ticket2h.rate-limit}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {

    Policy value();

    enum Policy {
        /** Đặt vé: người thật đặt vài đơn, bot gửi hàng trăm. */
        ORDERS,
        /** Vào phòng chờ và hỏi thứ tự. */
        WAITING_ROOM
    }
}
