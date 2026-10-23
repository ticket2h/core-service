package com.ticket2h.reporting;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.ticket2h.common.time.Times;

/**
 * SQL của hàng đợi {@code sales_rollup_queue} và hai bảng tổng hợp {@code sales_daily_items},
 * {@code sales_daily_orders} (ADR-016). Ngày bán tính theo múi giờ của sự kiện:
 * {@code (paid_at AT TIME ZONE e.time_zone)::date}. Đơn trả lúc 23:30 ngày 1/11 và 00:30 ngày 2/11 giờ Việt Nam
 * (16:30 và 17:30 UTC, cùng ngày 1/11) là hai ngày bán khác nhau; gom theo ngày UTC thì hai đơn rơi vào cùng một ngày.
 */
@Repository
class SalesRollupStore {

    /**
     * Khoá advisory (khoá theo một con số, không gắn với dòng nào) giữa job cộng dồn và việc tính lại từ đầu, để hai
     * việc không chạy chồng lên nhau. Tự nhả khi transaction kết thúc.
     */
    private static final long ROLLUP_LOCK = 0x7432_6873_616c_6573L;   // "t2hsales"

    /**
     * Một câu lệnh làm cả ba việc: lấy và xoá một lô đơn khỏi hàng đợi, cộng vé/doanh thu theo loại vé, cộng số đơn.
     * Câu lệnh là nguyên tử: hoặc cả ba cùng xảy ra, hoặc không gì cả, nên mỗi đơn được cộng đúng một lần.
     * {@code FOR UPDATE SKIP LOCKED}: chạy hai instance thì mỗi instance lấy một lô khác nhau.
     * {@code ORDER BY} trước khi INSERT để các dòng tổng hợp luôn bị khoá theo cùng một thứ tự (không deadlock).
     */
    private static final String APPLY_BATCH = """
            WITH taken AS (
                DELETE FROM sales_rollup_queue
                WHERE order_id IN (SELECT order_id FROM sales_rollup_queue
                                   ORDER BY enqueued_at, order_id
                                   LIMIT :limit
                                   FOR UPDATE SKIP LOCKED)
                RETURNING order_id
            ), sold AS (
                SELECT o.id, o.event_id, (o.paid_at AT TIME ZONE e.time_zone)::date AS sale_date, o.sales_channel
                FROM taken t
                JOIN orders o ON o.id = t.order_id
                JOIN events e ON e.id = o.event_id
                WHERE o.status IN ('PAID', 'FULFILLED')
            ), add_items AS (
                INSERT INTO sales_daily_items (event_id, sale_date, sales_channel, ticket_type_id, tickets, revenue)
                SELECT s.event_id, s.sale_date, s.sales_channel, oi.ticket_type_id,
                       sum(oi.quantity), sum(oi.quantity * oi.unit_price)
                FROM sold s JOIN order_items oi ON oi.order_id = s.id
                GROUP BY s.event_id, s.sale_date, s.sales_channel, oi.ticket_type_id
                ORDER BY s.event_id, s.sale_date, s.sales_channel, oi.ticket_type_id
                ON CONFLICT (event_id, sale_date, sales_channel, ticket_type_id) DO UPDATE
                    SET tickets = sales_daily_items.tickets + EXCLUDED.tickets,
                        revenue = sales_daily_items.revenue + EXCLUDED.revenue
            ), add_orders AS (
                INSERT INTO sales_daily_orders (event_id, sale_date, sales_channel, orders)
                SELECT event_id, sale_date, sales_channel, count(*)
                FROM sold
                GROUP BY event_id, sale_date, sales_channel
                ORDER BY event_id, sale_date, sales_channel
                ON CONFLICT (event_id, sale_date, sales_channel) DO UPDATE
                    SET orders = sales_daily_orders.orders + EXCLUDED.orders
            )
            SELECT count(*) FROM taken
            """;

    /** Tính lại từ bảng gốc. {@code :eventFilter} được thay bằng điều kiện cố định, không lấy từ input. */
    private static final String REBUILD_ITEMS = """
            INSERT INTO sales_daily_items (event_id, sale_date, sales_channel, ticket_type_id, tickets, revenue)
            SELECT o.event_id, (o.paid_at AT TIME ZONE e.time_zone)::date, o.sales_channel, oi.ticket_type_id,
                   sum(oi.quantity), sum(oi.quantity * oi.unit_price)
            FROM orders o
            JOIN events e ON e.id = o.event_id
            JOIN order_items oi ON oi.order_id = o.id
            WHERE o.status IN ('PAID', 'FULFILLED') %s
            GROUP BY 1, 2, 3, 4
            """;

    private static final String REBUILD_ORDERS = """
            INSERT INTO sales_daily_orders (event_id, sale_date, sales_channel, orders)
            SELECT o.event_id, (o.paid_at AT TIME ZONE e.time_zone)::date, o.sales_channel, count(*)
            FROM orders o
            JOIN events e ON e.id = o.event_id
            WHERE o.status IN ('PAID', 'FULFILLED') %s
            GROUP BY 1, 2, 3
            """;

    private final NamedParameterJdbcTemplate jdbc;

    SalesRollupStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void lock() {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(:key)", Map.of("key", ROLLUP_LOCK), Integer.class);
    }

    /** {@code ON CONFLICT DO NOTHING}: ghi hai lần cho cùng một đơn vẫn chỉ một dòng. */
    void enqueue(UUID orderId, Instant now) {
        jdbc.update("""
                INSERT INTO sales_rollup_queue (order_id, enqueued_at) VALUES (:orderId, :now)
                ON CONFLICT (order_id) DO NOTHING
                """, new MapSqlParameterSource().addValue("orderId", orderId).addValue("now", Times.utc(now)));
    }

    /** Số đơn đã lấy khỏi hàng đợi (đã cộng, trừ đơn không còn ở trạng thái đã bán). */
    int applyBatch(int limit) {
        Integer taken = jdbc.queryForObject(APPLY_BATCH, Map.of("limit", limit), Integer.class);
        return taken == null ? 0 : taken;
    }

    void rebuildEvent(UUID eventId) {
        Map<String, UUID> params = Map.of("eventId", eventId);
        jdbc.update("""
                DELETE FROM sales_rollup_queue q USING orders o WHERE o.id = q.order_id AND o.event_id = :eventId
                """, params);
        jdbc.update("DELETE FROM sales_daily_items WHERE event_id = :eventId", params);
        jdbc.update("DELETE FROM sales_daily_orders WHERE event_id = :eventId", params);
        jdbc.update(REBUILD_ITEMS.formatted("AND o.event_id = :eventId"), params);
        jdbc.update(REBUILD_ORDERS.formatted("AND o.event_id = :eventId"), params);
    }

    void rebuildAll() {
        jdbc.getJdbcTemplate().execute("TRUNCATE sales_rollup_queue, sales_daily_items, sales_daily_orders");
        jdbc.getJdbcTemplate().update(REBUILD_ITEMS.formatted(""));
        jdbc.getJdbcTemplate().update(REBUILD_ORDERS.formatted(""));
    }
}
