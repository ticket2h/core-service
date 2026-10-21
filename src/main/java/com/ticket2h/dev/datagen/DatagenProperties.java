package com.ticket2h.dev.datagen;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cỡ dữ liệu sinh ({@code ticket2h.datagen.*}). Mặc định đúng quy mô của lộ trình M4: hệ thống chạy 2 năm, 2 triệu
 * khách, 10 triệu đơn, 30 triệu lượt quét. {@code customers}, {@code organizers}, {@code staff} phải giống lúc chạy
 * công cụ sinh của identity-service (infra/scripts/datagen.sh truyền cùng giá trị cho cả hai).
 */
@ConfigurationProperties("ticket2h.datagen")
public record DatagenProperties(
        @DefaultValue("2000000") int customers,
        @DefaultValue("200") int organizers,
        @DefaultValue("600") int staff,
        @DefaultValue("2000") int events,
        @DefaultValue("10000000") long orders,
        /** Sự kiện lớn đang mở bán, của ban tổ chức mẫu Sky (đăng nhập organizer1@test.local để xem). */
        @DefaultValue("200000") int bigEventOrders,
        @DefaultValue("30000000") long checkins,
        @DefaultValue("4") int threads,
        @DefaultValue("20261021") long seed) {}
