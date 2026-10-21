package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticket2h.reporting.SalesRollup;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/** M4: báo cáo doanh thu của một sự kiện 200.000 đơn chạy dưới 1 giây (ADR-016). */
class SalesReportPerformanceIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalesReportPerformanceIT.class);

    @Autowired SalesRollup rollup;

    @Test
    @Tag("slow")
    void report_of_an_event_with_200k_orders_answers_within_one_second() {
        var event = testData.eventOnSale(e -> {});
        UUID standing = testData.gaTicketType(event, new BigDecimal("800000"), 1_000_000);
        UUID early = testData.gaTicketType(event, new BigDecimal("650000"), 1_000_000);
        Instant firstSale = START.minus(Duration.ofDays(30));
        bulkData.paidGaOrders(event.id(), standing, new BigDecimal("800000"), 120_000, 2, firstSale,
                Duration.ofSeconds(21));
        bulkData.paidGaOrders(event.id(), early, new BigDecimal("650000"), 80_000, 1, firstSale,
                Duration.ofSeconds(31));
        // Sự kiện khác cùng bảng, để báo cáo phải lọc đúng sự kiện chứ không phải đọc cả bảng
        var other = testData.eventOnSale(e -> {});
        UUID otherType = testData.gaTicketType(other, new BigDecimal("300000"), 1_000_000);
        bulkData.paidGaOrders(other.id(), otherType, new BigDecimal("300000"), 100_000, 3, firstSale,
                Duration.ofSeconds(25));
        bulkData.analyze();
        rollup.rebuild(event.id());   // dữ liệu nạp thẳng vào database, không qua hàng đợi

        Map<String, Object> expected = jdbc.queryForMap("""
                SELECT count(DISTINCT o.id) AS orders, sum(oi.quantity) AS tickets,
                       sum(oi.quantity * oi.unit_price) AS revenue
                FROM orders o JOIN order_items oi ON oi.order_id = o.id
                WHERE o.event_id = ? AND o.status IN ('PAID', 'FULFILLED')
                """, event.id());
        assertThat(((Number) expected.get("orders")).longValue()).isEqualTo(200_000);
        long directMillis = millis(() -> jdbc.queryForList("""
                SELECT (o.paid_at AT TIME ZONE e.time_zone)::date, sum(oi.quantity), sum(oi.quantity * oi.unit_price)
                FROM orders o JOIN events e ON e.id = o.event_id JOIN order_items oi ON oi.order_id = o.id
                WHERE o.event_id = ? AND o.status IN ('PAID', 'FULFILLED')
                GROUP BY 1
                """, event.id()));

        String token = testData.token(event.organizer().userId());
        for (String groupBy : new String[] {"DAY", "TICKET_TYPE", "CHANNEL"}) {
            String path = "/organizer/events/" + event.id() + "/sales?groupBy=" + groupBy;
            api.get(path, token);   // lượt đầu: nạp class, kết nối, kế hoạch truy vấn
            long worst = 0;
            JsonNode body = null;
            for (int i = 0; i < 3; i++) {
                long started = System.nanoTime();
                Api.Response response = api.get(path, token);
                worst = Math.max(worst, (System.nanoTime() - started) / 1_000_000);
                assertThat(response.status()).isEqualTo(200);
                body = response.json();
            }
            log.info("Báo cáo {} của sự kiện 200.000 đơn: chậm nhất {} ms (tính thẳng từ orders: {} ms)", groupBy,
                    worst, directMillis);
            assertThat(worst).as("thời gian báo cáo " + groupBy + " (ms)").isLessThan(1_000);
            JsonNode totals = body.get("totals");
            assertThat(totals.get("orders").asLong()).isEqualTo(200_000);
            assertThat(totals.get("tickets").asLong()).isEqualTo(((Number) expected.get("tickets")).longValue());
            assertThat(totals.get("revenue").decimalValue())
                    .isEqualByComparingTo((BigDecimal) expected.get("revenue"));
        }
    }

    private static long millis(Runnable action) {
        long started = System.nanoTime();
        action.run();
        return (System.nanoTime() - started) / 1_000_000;
    }
}
