package com.ticket2h.reporting;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Đọc báo cáo doanh thu từ hai bảng tổng hợp, không đụng tới {@code orders}: một sự kiện 200.000 đơn chỉ có vài trăm
 * dòng tổng hợp, nên câu nào cũng chạy trong vài mili giây (đo trong ADR-016).
 * Mọi đoạn SQL ghép vào câu ({@code ORDER BY}, điều kiện ngày) đều là hằng số viết sẵn trong file này, chọn theo enum.
 */
@Repository
class SalesReportStore {

    private final NamedParameterJdbcTemplate jdbc;

    SalesReportStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Totals totals(UUID eventId, LocalDate from, LocalDate to) {
        String range = dateRange(from, to);
        return jdbc.queryForObject("""
                SELECT (SELECT coalesce(sum(orders), 0) FROM sales_daily_orders
                        WHERE event_id = :eventId %1$s) AS orders,
                       coalesce(sum(tickets), 0) AS tickets,
                       coalesce(sum(revenue), 0) AS revenue
                FROM sales_daily_items
                WHERE event_id = :eventId %1$s
                """.formatted(range), params(eventId, from, to),
                (rs, i) -> new Totals(rs.getLong("orders"), rs.getLong("tickets"), rs.getBigDecimal("revenue")));
    }

    List<Row> rows(UUID eventId, SalesGroupBy groupBy, SalesSort sort, SortDirection direction, LocalDate from,
            LocalDate to) {
        String range = dateRange(from, to);
        String sql = switch (groupBy) {
            case DAY -> """
                    SELECT i.sale_date::text AS key, NULL AS label, o.orders, i.tickets, i.revenue
                    FROM (SELECT sale_date, sum(tickets) AS tickets, sum(revenue) AS revenue
                          FROM sales_daily_items WHERE event_id = :eventId %1$s GROUP BY sale_date) i
                    JOIN (SELECT sale_date, sum(orders) AS orders
                          FROM sales_daily_orders WHERE event_id = :eventId %1$s GROUP BY sale_date) o
                         ON o.sale_date = i.sale_date
                    ORDER BY %2$s
                    """;
            case CHANNEL -> """
                    SELECT i.sales_channel AS key, NULL AS label, o.orders, i.tickets, i.revenue
                    FROM (SELECT sales_channel, sum(tickets) AS tickets, sum(revenue) AS revenue
                          FROM sales_daily_items WHERE event_id = :eventId %1$s GROUP BY sales_channel) i
                    JOIN (SELECT sales_channel, sum(orders) AS orders
                          FROM sales_daily_orders WHERE event_id = :eventId %1$s GROUP BY sales_channel) o
                         ON o.sales_channel = i.sales_channel
                    ORDER BY %2$s
                    """;
            // Mọi loại vé của sự kiện, kể cả loại chưa bán được vé nào. Không có số đơn: một đơn mua nhiều loại vé
            // sẽ bị đếm ở nhiều dòng.
            case TICKET_TYPE -> """
                    SELECT tt.id::text AS key, tt.name AS label, NULL::bigint AS orders,
                           coalesce(i.tickets, 0) AS tickets, coalesce(i.revenue, 0) AS revenue
                    FROM ticket_types tt
                    LEFT JOIN (SELECT ticket_type_id, sum(tickets) AS tickets, sum(revenue) AS revenue
                               FROM sales_daily_items WHERE event_id = :eventId %1$s GROUP BY ticket_type_id) i
                              ON i.ticket_type_id = tt.id
                    WHERE tt.event_id = :eventId
                    ORDER BY %2$s
                    """;
        };
        return jdbc.query(sql.formatted(range, orderBy(groupBy, sort, direction)), params(eventId, from, to),
                SalesReportStore::row);
    }

    /**
     * {@code ORDER BY} dựng từ enum. Cột cuối cùng là khoá của nhóm, để thứ tự luôn xác định khi các giá trị bằng
     * nhau.
     */
    static String orderBy(SalesGroupBy groupBy, SalesSort sort, SortDirection direction) {
        String key = switch (groupBy) {
            case DAY -> "key";               // 'YYYY-MM-DD' sắp theo chữ cũng là theo ngày
            case CHANNEL -> "key";
            case TICKET_TYPE -> "label, key";
        };
        String column = switch (sort) {
            case KEY -> key;
            case ORDERS -> "orders";
            case TICKETS -> "tickets";
            case REVENUE -> "revenue";
        };
        String dir = direction == SortDirection.ASC ? "ASC" : "DESC";
        if (sort == SalesSort.KEY) {
            return groupBy == SalesGroupBy.TICKET_TYPE ? "label " + dir + ", key " + dir : "key " + dir;
        }
        return column + " " + dir + " NULLS LAST, " + key;
    }

    private static String dateRange(LocalDate from, LocalDate to) {
        return (from == null ? "" : "AND sale_date >= :from ") + (to == null ? "" : "AND sale_date <= :to");
    }

    private static MapSqlParameterSource params(UUID eventId, LocalDate from, LocalDate to) {
        return new MapSqlParameterSource().addValue("eventId", eventId).addValue("from", from).addValue("to", to);
    }

    private static Row row(ResultSet rs, int i) throws SQLException {
        long orders = rs.getLong("orders");
        Long ordersOrNull = rs.wasNull() ? null : orders;   // wasNull() chỉ đúng ngay sau lần đọc cột đó
        return new Row(rs.getString("key"), rs.getString("label"), ordersOrNull, rs.getLong("tickets"),
                rs.getBigDecimal("revenue"));
    }

    record Totals(long orders, long tickets, BigDecimal revenue) {}

    /** {@code orders} là {@code null} khi gom theo loại vé. */
    record Row(String key, String label, Long orders, long tickets, BigDecimal revenue) {}
}
