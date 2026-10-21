package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ticket2h.common.paging.KeysetCursor;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M4: mở trang thứ 10.000 của danh sách vẫn nhanh (ADR-018). Sự kiện 250.000 đơn, 20 đơn mỗi trang: trang 10.000 bắt
 * đầu sau đơn thứ 199.980. Kiểm tra cả cơ chế (kế hoạch truy vấn chỉ đọc ~21 dòng của index, OFFSET thì đọc 200.000
 * dòng) lẫn thời gian trả lời của API.
 */
class OrderPagingIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OrderPagingIT.class);
    private static final int PAGE = 20;
    private static final int DEPTH = (10_000 - 1) * PAGE;   // số đơn đứng trước trang 10.000

    /** Giống câu SQL của OrderListStore.page (không lọc trạng thái). */
    private static final String KEYSET = """
            SELECT o.id FROM orders o
            WHERE o.event_id = ? AND (o.created_at, o.id) < (?, ?)
            ORDER BY o.created_at DESC, o.id DESC LIMIT 21
            """;
    private static final String OFFSET = """
            SELECT o.id FROM orders o
            WHERE o.event_id = ?
            ORDER BY o.created_at DESC, o.id DESC LIMIT 21 OFFSET ?
            """;

    @Test
    @Tag("slow")
    void page_10000_of_an_event_with_250k_orders_reads_only_its_own_rows() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 1_000_000);
        bulkData.paidGaOrders(event.id(), ga, new BigDecimal("500000"), 250_000, 1, START.minus(Duration.ofDays(20)),
                Duration.ofMillis(5));
        var other = testData.eventOnSale(e -> {});
        UUID otherType = testData.gaTicketType(other, new BigDecimal("500000"), 1_000_000);
        bulkData.paidGaOrders(other.id(), otherType, new BigDecimal("500000"), 100_000, 1, START, Duration.ofMillis(5));
        bulkData.analyze();
        identity.synthesizeUnknownAccounts(true);

        // Đơn cuối của trang 9.999: client đi lần lượt từng trang thì sẽ có đúng cursor này
        Map<String, Object> last = jdbc.queryForMap("""
                SELECT id, created_at FROM orders WHERE event_id = ?
                ORDER BY created_at DESC, id DESC OFFSET ? LIMIT 1
                """, event.id(), DEPTH - 1);
        UUID lastId = (UUID) last.get("id");
        OffsetDateTime lastAt = ((java.sql.Timestamp) last.get("created_at")).toInstant()
                .atOffset(java.time.ZoneOffset.UTC);

        long keysetRows = rowsRead(KEYSET, event.id(), lastAt, lastId);
        long offsetRows = rowsRead(OFFSET, event.id(), DEPTH);
        log.info("Trang 10.000: keyset đọc {} dòng của index, OFFSET đọc {} dòng", keysetRows, offsetRows);
        assertThat(keysetRows).isLessThanOrEqualTo(PAGE + 1);
        assertThat(offsetRows).isGreaterThan(DEPTH);

        String cursor = new KeysetCursor(lastAt.toInstant(), lastId).encode();
        String path = "/organizer/events/" + event.id() + "/orders?limit=" + PAGE + "&cursor=" + cursor;
        String token = testData.token(event.organizer().userId());
        api.get(path, token);   // lượt đầu: kế hoạch truy vấn, kết nối
        List<Long> millis = new ArrayList<>();
        JsonNode items = null;
        for (int i = 0; i < 5; i++) {
            long started = System.nanoTime();
            Api.Response response = api.get(path, token);
            millis.add((System.nanoTime() - started) / 1_000_000);
            assertThat(response.status()).isEqualTo(200);
            items = response.json().get("items");
        }
        long median = millis.stream().sorted().toList().get(2);
        log.info("API trang 10.000: trung vị {} ms ({})", median, millis);
        assertThat(median).as("thời gian trả lời trang 10.000 (ms)").isLessThan(250);
        assertThat(items).hasSize(PAGE);
        assertThat(items.get(0).get("orderId").asString()).isEqualTo(jdbc.queryForObject("""
                SELECT id::text FROM orders WHERE event_id = ? ORDER BY created_at DESC, id DESC OFFSET ? LIMIT 1
                """, String.class, event.id(), DEPTH));
    }

    /** Tổng số dòng các nút quét bảng/index thật sự đọc ra, theo EXPLAIN ANALYZE. */
    private long rowsRead(String sql, Object... params) {
        String plan = jdbc.queryForObject("EXPLAIN (ANALYZE, FORMAT JSON) " + sql, String.class, params);
        JsonNode root = JsonMapper.builder().build().readTree(plan).get(0).get("Plan");
        return scannedRows(root);
    }

    private static long scannedRows(JsonNode node) {
        long rows = node.get("Node Type").asString().contains("Scan")
                ? node.get("Actual Rows").asLong() * Math.max(1, node.path("Actual Loops").asLong(1)) : 0;
        for (JsonNode child : node.path("Plans")) {
            rows += scannedRows(child);
        }
        return rows;
    }
}
