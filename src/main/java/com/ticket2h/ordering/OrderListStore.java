package com.ticket2h.ordering;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ticket2h.common.paging.KeysetCursor;
import com.ticket2h.common.time.Times;

/**
 * Danh sách đơn của một sự kiện cho ban tổ chức, phân trang keyset (ADR-018). Trang sau đi tiếp từ đơn cuối của trang
 * trước: {@code (created_at, id) < (:afterAt, :afterId)}. PostgreSQL dùng điều kiện đó để nhảy thẳng tới đúng chỗ
 * trong index {@code idx_orders_event_created (event_id, created_at DESC, id DESC)} rồi đọc đúng {@code limit} dòng,
 * nên trang thứ 10.000 nhanh như trang đầu. OFFSET thì phải đọc rồi bỏ qua mọi dòng phía trước.
 */
@Repository
class OrderListStore {

    private final NamedParameterJdbcTemplate jdbc;

    OrderListStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code status} null: mọi trạng thái. {@code after} null: trang đầu. Mới tạo trước. */
    List<OrderListRow> page(UUID eventId, OrderStatus status, KeysetCursor after, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("eventId", eventId)
                .addValue("limit", limit);
        StringBuilder where = new StringBuilder("o.event_id = :eventId");
        if (status != null) {
            where.append(" AND o.status = :status");
            params.addValue("status", status.name());
        }
        if (after != null) {
            where.append(" AND (o.created_at, o.id) < (:afterAt, :afterId)");
            params.addValue("afterAt", Times.utc(after.at())).addValue("afterId", after.id());
        }
        return jdbc.query("""
                SELECT o.id, o.user_id, o.status, o.sales_channel, o.total_amount, o.created_at, o.paid_at,
                       (SELECT coalesce(sum(oi.quantity), 0) FROM order_items oi WHERE oi.order_id = o.id) AS tickets
                FROM orders o
                WHERE %s
                ORDER BY o.created_at DESC, o.id DESC
                LIMIT :limit
                """.formatted(where), params, (rs, i) -> new OrderListRow(
                        rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        OrderStatus.valueOf(rs.getString("status")),
                        SalesChannel.valueOf(rs.getString("sales_channel")),
                        rs.getBigDecimal("total_amount"), rs.getInt("tickets"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                        toInstant(rs.getObject("paid_at", OffsetDateTime.class))));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    record OrderListRow(UUID orderId, UUID buyerId, OrderStatus status, SalesChannel salesChannel,
            BigDecimal totalAmount, int tickets, Instant createdAt, Instant paidAt) {}
}
