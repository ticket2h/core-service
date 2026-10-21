package com.ticket2h.dev.datagen;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;

import com.ticket2h.reporting.SalesRollup;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.Invariants;
import com.ticket2h.ticketing.CheckinLogPartitions;

/** Công cụ sinh dữ liệu (ADR-021), ở quy mô nhỏ: dữ liệu sinh ra phải thoả mọi bất biến như dữ liệu thật. */
class CoreDataGeneratorIT extends IntegrationTest {

    private static final DatagenProperties SMALL = new DatagenProperties(3_000, 5, 20, 30, 20_000, 3_000, 30_000, 2,
            42);

    @Autowired DataSource dataSource;
    @Autowired SalesRollup salesRollup;
    @Autowired CheckinLogPartitions partitions;
    @Autowired ConfigurableApplicationContext context;

    @Test
    void generated_data_is_consistent_and_a_second_run_adds_nothing() throws Exception {
        clock.set(Instant.parse("2026-10-21T03:00:00Z"));
        CoreDataGenerator generator = new CoreDataGenerator(SMALL, dataSource, jdbc, salesRollup, partitions, clock,
                context);

        generator.generate();

        long orders = count("orders");
        assertThat(orders).isBetween(18_000L, 20_000L);
        assertThat(count("events")).isEqualTo(30);
        assertThat(count("venues")).isEqualTo(6);
        Invariants.assertConsistent(jdbc);
        // Đơn đã giao có đủ vé, đơn hết hạn/huỷ thì không
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM orders o
                WHERE o.status = 'FULFILLED' AND (SELECT sum(quantity) FROM order_items WHERE order_id = o.id)
                      <> (SELECT count(*) FROM tickets WHERE order_id = o.id)
                """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM tickets t JOIN orders o ON o.id = t.order_id WHERE o.status <> 'FULFILLED'
                """, Long.class)).isZero();
        // Mỗi vé đã vào cổng có đúng một lượt quét OK; tổng số lượt quét gần con số đặt ra
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tickets WHERE status = 'USED'", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM checkin_logs WHERE result = 'OK'", Long.class));
        assertThat(count("checkin_logs")).isBetween(27_000L, 33_000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM checkin_logs_default", Long.class)).isZero();
        // Bảng tổng hợp khớp bảng gốc
        Map<String, Object> direct = jdbc.queryForMap("""
                SELECT sum(oi.quantity) AS tickets, sum(oi.quantity * oi.unit_price) AS revenue
                FROM orders o JOIN order_items oi ON oi.order_id = o.id WHERE o.status IN ('PAID', 'FULFILLED')
                """);
        Map<String, Object> rolled = jdbc.queryForMap(
                "SELECT sum(tickets) AS tickets, sum(revenue) AS revenue FROM sales_daily_items");
        assertThat(((Number) rolled.get("tickets")).longValue())
                .isEqualTo(((Number) direct.get("tickets")).longValue());
        assertThat((java.math.BigDecimal) rolled.get("revenue"))
                .isEqualByComparingTo((java.math.BigDecimal) direct.get("revenue"));
        // Sự kiện lớn đang mở bán, của ban tổ chức mẫu Sky, còn vé để mua thử
        Map<String, Object> big = jdbc.queryForMap("""
                SELECT e.organizer_id::text AS organizer, e.sale_closes_at > ? AS on_sale,
                       (SELECT count(*) FROM orders WHERE event_id = e.id) AS orders,
                       (SELECT sum(available_quantity) FROM ticket_types WHERE event_id = e.id) AS ga_left,
                       (SELECT count(*) FROM seats WHERE event_id = e.id AND status = 'AVAILABLE') AS seats_left
                FROM events e WHERE e.title = 'Đại nhạc hội Ticket2h 2026'
                """, java.sql.Timestamp.from(clock.instant()));
        assertThat(big.get("organizer")).isEqualTo("0a000000-0000-4000-8000-000000000001");
        assertThat(big.get("on_sale")).isEqualTo(true);
        assertThat(((Number) big.get("orders")).longValue()).isEqualTo(3_000);
        assertThat(((Number) big.get("ga_left")).longValue()).isPositive();
        assertThat(((Number) big.get("seats_left")).longValue()).isPositive();
        // Người mua là tài khoản sinh bởi identity-service (cùng công thức id)
        assertThat(jdbc.queryForList("SELECT DISTINCT left(user_id::text, 8) FROM orders", String.class))
                .containsExactly("0d000000");

        generator.generate();

        assertThat(count("orders")).isEqualTo(orders);
    }

    /** Hợp đồng với identity-service: cùng công thức, cùng id (DatagenIdsTest bên identity kiểm tra cùng giá trị). */
    @Test
    void shared_ids_match_the_contract() {
        assertThat(DatagenIds.customer(1)).hasToString("0d000000-0000-4000-8000-000000000001");
        assertThat(DatagenIds.customer(2_000_000)).hasToString("0d000000-0000-4000-8000-0000001e8480");
        assertThat(DatagenIds.organizer(200)).hasToString("0e000000-0000-4000-8000-0000000000c8");
        assertThat(DatagenIds.organizerAccount(7)).hasToString("0c000000-0000-4000-8000-000000000007");
        assertThat(DatagenIds.staff(600)).hasToString("0f000000-0000-4000-8000-000000000258");
        List<java.util.UUID> internal = List.of(DatagenIds.of(0x14, 5, 1), DatagenIds.of(0x14, 5, 2),
                DatagenIds.of(0x17, 5, 1), DatagenIds.of(0x14, 6, 1));
        assertThat(internal).doesNotHaveDuplicates();
        // Web hiện 8 ký tự đầu làm mã ngắn: id nội bộ không được giống nhau ở phần đầu
        assertThat(internal.stream().map(id -> id.toString().substring(0, 8)).toList()).doesNotHaveDuplicates();
        assertThat(internal).allMatch(id -> id.version() == 8 && id.variant() == 2);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
