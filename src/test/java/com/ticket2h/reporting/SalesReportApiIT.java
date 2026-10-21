package com.ticket2h.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.ordering.SalesChannel;
import com.ticket2h.payment.Payment;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

import tools.jackson.databind.JsonNode;

/** Báo cáo doanh thu (ADR-016): số liệu, múi giờ, cộng đúng một lần, quyền, tham số sắp xếp. */
class SalesReportApiIT extends IntegrationTest {

    private static final BigDecimal GA_PRICE = new BigDecimal("500000");
    private static final BigDecimal VIP_PRICE = new BigDecimal("1500000");

    @Autowired SalesRollup rollup;

    @Test
    void report_counts_paid_orders_by_day_ticket_type_and_channel() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, GA_PRICE, 100);
        UUID vip = testData.seatedTicketType(event, VIP_PRICE);
        List<UUID> seats = testData.seats(event, vip, 4);

        // Đơn 1: 2 GA + 1 ghế VIP qua web. Đơn 2: 3 GA qua app. Đơn 3: tạo nhưng không trả tiền.
        testData.paidOrderViaWebhook(testData.user(), CreateOrderRequest.of(event.id(),
                CreateOrderRequest.Item.ga(ga, 2), CreateOrderRequest.Item.seats(vip, List.of(seats.get(0)))));
        testData.paidOrderViaWebhook(testData.user(),
                CreateOrderRequest.ga(event.id(), ga, 3).withChannel(SalesChannel.MOBILE_APP));
        testData.placeOrder(testData.user(), CreateOrderRequest.ga(event.id(), ga, 1));

        assertThat(rollup.applyPending()).isEqualTo(2);

        JsonNode byDay = report(event, "groupBy=DAY").json();
        assertThat(byDay.get("totals").get("orders").asLong()).isEqualTo(2);
        assertThat(byDay.get("totals").get("tickets").asLong()).isEqualTo(6);
        assertThat(byDay.get("totals").get("revenue").decimalValue()).isEqualByComparingTo("4000000");
        assertThat(byDay.get("currency").asString()).isEqualTo("VND");
        assertThat(byDay.get("rows")).hasSize(1);
        assertThat(byDay.get("rows").get(0).get("key").asString()).isEqualTo("2026-11-01");   // START = 19:00 giờ VN

        Map<String, JsonNode> byType = rowsByKey(report(event, "groupBy=TICKET_TYPE").json());
        assertThat(byType.get(ga.toString()).get("tickets").asLong()).isEqualTo(5);
        assertThat(byType.get(ga.toString()).get("revenue").decimalValue()).isEqualByComparingTo("2500000");
        assertThat(byType.get(vip.toString()).get("label").asString()).startsWith("VIP");
        assertThat(byType.get(vip.toString()).get("tickets").asLong()).isEqualTo(1);
        assertThat(byType.get(ga.toString()).get("orders").isNull()).isTrue();

        Map<String, JsonNode> byChannel = rowsByKey(report(event, "groupBy=CHANNEL").json());
        assertThat(byChannel.get("WEB").get("orders").asLong()).isEqualTo(1);
        assertThat(byChannel.get("WEB").get("revenue").decimalValue()).isEqualByComparingTo("2500000");
        assertThat(byChannel.get("MOBILE_APP").get("orders").asLong()).isEqualTo(1);
        assertThat(byChannel.get("MOBILE_APP").get("tickets").asLong()).isEqualTo(3);
    }

    @Test
    void ticket_types_without_sales_are_listed_with_zero() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, GA_PRICE, 100);

        JsonNode rows = report(event, "groupBy=TICKET_TYPE").json().get("rows");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("key").asString()).isEqualTo(ga.toString());
        assertThat(rows.get(0).get("tickets").asLong()).isZero();
        assertThat(rows.get(0).get("revenue").decimalValue()).isEqualByComparingTo("0");
    }

    /** Bẫy múi giờ: 23:30 và 00:30 giờ Việt Nam là hai ngày khác nhau, dù theo giờ UTC vẫn cùng một ngày. */
    @Test
    void sale_day_follows_the_event_time_zone_not_utc() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, GA_PRICE, 100);

        clock.set(Instant.parse("2026-11-01T16:30:00Z"));   // 23:30 ngày 1/11 ở Việt Nam
        testData.paidOrderViaWebhook(testData.user(), CreateOrderRequest.ga(event.id(), ga, 1));
        clock.set(Instant.parse("2026-11-01T17:30:00Z"));   // 00:30 ngày 2/11 ở Việt Nam
        testData.paidOrderViaWebhook(testData.user(), CreateOrderRequest.ga(event.id(), ga, 2));
        rollup.applyPending();

        Map<String, JsonNode> byDay = rowsByKey(report(event, "groupBy=DAY").json());
        assertThat(byDay.keySet()).containsExactlyInAnyOrder("2026-11-01", "2026-11-02");
        assertThat(byDay.get("2026-11-01").get("tickets").asLong()).isEqualTo(1);
        assertThat(byDay.get("2026-11-02").get("tickets").asLong()).isEqualTo(2);

        JsonNode secondDayOnly = report(event, "groupBy=DAY&from=2026-11-02&to=2026-11-02").json();
        assertThat(secondDayOnly.get("totals").get("tickets").asLong()).isEqualTo(2);
        assertThat(secondDayOnly.get("rows")).hasSize(1);
    }

    @Test
    void duplicate_webhooks_and_repeated_job_runs_count_an_order_once() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, GA_PRICE, 100);
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.ga(event.id(), ga, 2)).order().getId();
        Payment payment = testData.initiatePayment(buyer, orderId);
        Map<String, Object> webhook = testData.succeededWebhook(payment);

        for (int i = 0; i < 3; i++) {
            assertThat(testData.sendWebhook(webhook).status()).isEqualTo(200);
            rollup.applyPending();
        }
        rollup.applyPending();

        JsonNode totals = report(event, "groupBy=DAY").json().get("totals");
        assertThat(totals.get("orders").asLong()).isEqualTo(1);
        assertThat(totals.get("tickets").asLong()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_rollup_queue", Integer.class)).isZero();
    }

    @Test
    void rebuilding_from_orders_gives_the_same_numbers_as_the_incremental_job() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, GA_PRICE, 100);
        for (int i = 1; i <= 5; i++) {
            clock.advance(java.time.Duration.ofHours(7));
            testData.paidOrderViaWebhook(testData.user(), CreateOrderRequest.ga(event.id(), ga, i % 3 + 1)
                    .withChannel(i % 2 == 0 ? SalesChannel.MOBILE_APP : SalesChannel.WEB));
        }
        rollup.applyPending();
        String incremental = report(event, "groupBy=DAY").body();

        rollup.rebuild(event.id());

        assertThat(report(event, "groupBy=DAY").body()).isEqualTo(incremental);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales_daily_items WHERE event_id = ?", Integer.class,
                event.id())).isGreaterThan(1);
    }

    @Test
    void rows_are_sorted_by_the_requested_column() {
        var event = testData.eventOnSale(e -> {});
        UUID cheap = testData.gaTicketType(event, new BigDecimal("100000"), 100);
        UUID pricey = testData.gaTicketType(event, new BigDecimal("900000"), 100);
        testData.paidOrderViaWebhook(testData.user(), CreateOrderRequest.ga(event.id(), cheap, 4));
        testData.paidOrderViaWebhook(testData.user(), CreateOrderRequest.ga(event.id(), pricey, 1));
        rollup.applyPending();

        assertThat(keys(report(event, "groupBy=TICKET_TYPE&sort=REVENUE&direction=DESC")))
                .containsExactly(pricey.toString(), cheap.toString());
        assertThat(keys(report(event, "groupBy=TICKET_TYPE&sort=TICKETS&direction=DESC")))
                .containsExactly(cheap.toString(), pricey.toString());
        // Mặc định khi gom theo loại vé: doanh thu giảm dần
        JsonNode defaults = report(event, "groupBy=TICKET_TYPE").json();
        assertThat(defaults.get("sort").asString()).isEqualTo("REVENUE");
        assertThat(defaults.get("direction").asString()).isEqualTo("DESC");
    }

    /** Cột sắp xếp chỉ nhận giá trị trong danh sách, chuỗi khác (kể cả SQL) bị từ chối, không chạm tới câu SQL. */
    @Test
    void unknown_sort_values_are_rejected_instead_of_reaching_sql() {
        var event = testData.eventOnSale(e -> {});

        for (String query : List.of("sort=revenue%3B%20DROP%20TABLE%20orders", "sort=total_amount",
                "direction=sideways", "groupBy=USER", "from=hom-qua")) {
            Api.Response response = report(event, query);
            assertThat(response.status()).as(query).isEqualTo(400);
            assertThat(response.code()).as(query).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(report(event, "groupBy=TICKET_TYPE&sort=ORDERS").status()).isEqualTo(400);
        assertThat(report(event, "from=2026-11-05&to=2026-11-01").status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
    }

    @Test
    void organizer_cannot_read_another_organizers_sales() {
        var event = testData.eventOnSale(e -> {});
        var other = testData.organizer();

        Api.Response response = api.get("/organizer/events/" + event.id() + "/sales", testData.token(other.userId()));

        assertThat(response.status()).isEqualTo(403);
        assertThat(api.get("/organizer/events/" + event.id() + "/sales", testData.token(testData.user())).status())
                .isEqualTo(403);
    }

    @Test
    void channel_is_taken_from_the_order_request_and_unknown_channels_are_rejected() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, GA_PRICE, 100);
        String token = testData.token(testData.user());
        Map<String, Object> item = Map.of("ticketTypeId", ga, "quantity", 1);

        Api.Response app = api.post("/orders", token, Map.of("eventId", event.id(), "items", List.of(item),
                "channel", "MOBILE_APP"), Map.of("Idempotency-Key", "k-app"));
        Api.Response web = api.post("/orders", token, Map.of("eventId", event.id(), "items", List.of(item)),
                Map.of("Idempotency-Key", "k-web"));
        Api.Response unknown = api.post("/orders", token, Map.of("eventId", event.id(), "items", List.of(item),
                "channel", "TIKTOK"), Map.of("Idempotency-Key", "k-unknown"));

        assertThat(app.status()).isEqualTo(201);
        assertThat(web.status()).isEqualTo(201);
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT sales_channel FROM orders WHERE id = ?", String.class,
                UUID.fromString(app.json().get("orderId").asString()))).isEqualTo("MOBILE_APP");
        assertThat(jdbc.queryForObject("SELECT sales_channel FROM orders WHERE id = ?", String.class,
                UUID.fromString(web.json().get("orderId").asString()))).isEqualTo("WEB");
    }

    private Api.Response report(EventRef event, String query) {
        return api.get("/organizer/events/" + event.id() + "/sales?" + query,
                testData.token(event.organizer().userId()));
    }

    private static Map<String, JsonNode> rowsByKey(JsonNode report) {
        Map<String, JsonNode> rows = new java.util.LinkedHashMap<>();
        report.get("rows").forEach(row -> rows.put(row.get("key").asString(), row));
        return rows;
    }

    private static List<String> keys(Api.Response response) {
        List<String> keys = new java.util.ArrayList<>();
        response.json().get("rows").forEach(row -> keys.add(row.get("key").asString()));
        return keys;
    }
}
