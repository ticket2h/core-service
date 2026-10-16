package com.ticket2h.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/** Các bất biến ở phụ lục E1, chạy sau test đồng thời. */
public final class Invariants {

    public static void assertConsistent(JdbcTemplate jdbc) {
        // Không ghế nào thuộc hai vé còn hiệu lực
        assertThat(jdbc.queryForList("""
                SELECT seat_id FROM tickets
                WHERE seat_id IS NOT NULL AND status <> 'VOID'
                GROUP BY seat_id HAVING count(*) > 1
                """)).as("ghế thuộc nhiều vé").isEmpty();

        // Tồn kho GA khớp với số vé đang giữ hoặc đã bán
        List<Map<String, Object>> gaMismatch = jdbc.queryForList("""
                SELECT tt.id, tt.total_quantity, tt.available_quantity, coalesce(sum(oi.quantity)
                       FILTER (WHERE o.status IN ('PENDING_PAYMENT', 'PAID', 'FULFILLED')), 0) AS held_or_sold
                FROM ticket_types tt
                LEFT JOIN order_items oi ON oi.ticket_type_id = tt.id
                LEFT JOIN orders o ON o.id = oi.order_id
                WHERE tt.kind = 'GA'
                GROUP BY tt.id
                HAVING tt.total_quantity <> tt.available_quantity + coalesce(sum(oi.quantity)
                       FILTER (WHERE o.status IN ('PENDING_PAYMENT', 'PAID', 'FULFILLED')), 0)
                """);
        assertThat(gaMismatch).as("tồn kho GA lệch").isEmpty();

        // Hạn mức khớp với số vé của các đơn còn hiệu lực
        assertThat(jdbc.queryForList("""
                SELECT q.user_id, q.event_id FROM purchase_quotas q
                LEFT JOIN (SELECT o.user_id, o.event_id, sum(oi.quantity) AS n
                           FROM orders o JOIN order_items oi ON oi.order_id = o.id
                           WHERE o.status IN ('PENDING_PAYMENT', 'PAID', 'FULFILLED')
                           GROUP BY o.user_id, o.event_id) used
                       ON used.user_id = q.user_id AND used.event_id = q.event_id
                WHERE q.reserved <> coalesce(used.n, 0)
                """)).as("hạn mức lệch").isEmpty();

        // Ghế SOLD thuộc đơn đã trả tiền; ghế HELD thuộc đơn đang chờ
        assertThat(jdbc.queryForList("""
                SELECT s.id FROM seats s JOIN orders o ON o.id = s.order_id
                WHERE (s.status = 'SOLD' AND o.status NOT IN ('PAID', 'FULFILLED'))
                   OR (s.status = 'HELD' AND o.status <> 'PENDING_PAYMENT')
                """)).as("ghế ở trạng thái nửa vời").isEmpty();
    }

    private Invariants() {}
}
