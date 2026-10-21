package com.ticket2h.support;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.ticket2h.common.time.Times;

/**
 * Dữ liệu lớn cho test M4 (hàng trăm nghìn dòng), sinh ngay trong PostgreSQL bằng {@code generate_series}: không
 * dòng nào đi qua bộ nhớ của JVM test, nên chạy được cả trong test giới hạn heap 256MB. Dữ liệu chỉ thoả ràng buộc
 * của bảng, không đi qua nghiệp vụ (không trừ bộ đếm GA, không giữ hạn mức). Bean trong test, như {@link TestData}.
 */
public class BulkData {

    private final JdbcTemplate jdbc;

    public BulkData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code n} đơn FULFILLED của {@code n} người mua khác nhau, mỗi đơn {@code quantity} vé loại {@code ticketTypeId}.
     * Đơn thứ g trả tiền lúc {@code firstPaidAt + (g - 1) * step}; cứ 3 đơn thì 1 đơn bán qua MOBILE_APP.
     */
    public void paidGaOrders(UUID eventId, UUID ticketTypeId, BigDecimal price, int n, int quantity,
            Instant firstPaidAt, Duration step) {
        jdbc.update("""
                WITH new_orders AS (
                    INSERT INTO orders (id, user_id, event_id, status, total_amount, currency, idempotency_key,
                                        request_hash, expires_at, paid_at, fulfilled_at, created_at, updated_at,
                                        sales_channel)
                    SELECT gen_random_uuid(), gen_random_uuid(), ?, 'FULFILLED', ?, 'VND', 'bulk-' || g,
                           repeat('0', 64), t.paid + interval '9 minutes', t.paid, t.paid,
                           t.paid - interval '1 minute', t.paid,
                           CASE WHEN g % 3 = 0 THEN 'MOBILE_APP' ELSE 'WEB' END
                    FROM generate_series(1, ?) g
                    CROSS JOIN LATERAL (SELECT ?::timestamptz + (g - 1) * ?::interval AS paid) t
                    RETURNING id
                )
                INSERT INTO order_items (id, order_id, ticket_type_id, seat_id, quantity, unit_price)
                SELECT gen_random_uuid(), id, ?, NULL, ?, ? FROM new_orders
                """, eventId, price.multiply(BigDecimal.valueOf(quantity)), n, Times.utc(firstPaidAt),
                step.toString(), ticketTypeId, quantity, price);
    }

    /** Phát vé cho mọi đơn FULFILLED của sự kiện chưa có vé: mỗi đơn vị trong order_items một vé VALID. */
    public int issueTickets(UUID eventId) {
        return jdbc.update("""
                INSERT INTO tickets (id, order_id, order_item_id, item_seq, event_id, ticket_type_id, seat_id,
                                     owner_user_id, status, token_version, issued_at)
                SELECT gen_random_uuid(), oi.order_id, oi.id, s.seq, o.event_id, oi.ticket_type_id, oi.seat_id,
                       o.user_id, 'VALID', 1, o.fulfilled_at
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                CROSS JOIN LATERAL generate_series(1, oi.quantity) s(seq)
                WHERE o.event_id = ? AND o.status = 'FULFILLED'
                ON CONFLICT (order_item_id, item_seq) DO NOTHING
                """, eventId);
    }

    /** {@code n} lượt quét OK của nhân viên {@code staffId} ở cổng A, lượt thứ g lúc {@code first + (g - 1) * step}. */
    public void checkinLogs(UUID eventId, UUID staffId, int n, Instant first, Duration step) {
        jdbc.update("""
                INSERT INTO checkin_logs (ticket_id, event_id, staff_user_id, gate, result, scanned_at)
                SELECT gen_random_uuid(), ?, ?, 'A', 'OK', ?::timestamptz + (g - 1) * ?::interval
                FROM generate_series(1, ?) g
                """, eventId, staffId, Times.utc(first), step.toString(), n);
    }

    public void analyze() {
        jdbc.execute("ANALYZE");
    }
}
