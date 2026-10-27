package com.ticket2h.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.IllegalTransactionStateException;

import com.ticket2h.common.security.Role;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.ordering.OrderExpiryJob;
import com.ticket2h.payment.Payment;
import com.ticket2h.payment.RefundJob;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.OrganizerRef;

import tools.jackson.databind.JsonNode;

/** Lịch sử thao tác (ADR-023): ai làm gì, lúc nào, trên đối tượng nào; chỉ ghi thêm; ADMIN xem được. */
class AuditLogIT extends IntegrationTest {

    @Autowired AuditLog auditLog;
    @Autowired AuditLogPartitions partitions;
    @Autowired OrderExpiryJob expiryJob;
    @Autowired RefundJob refundJob;

    @Test
    void organizer_changes_are_recorded_with_who_did_what() {
        OrganizerRef org = testData.organizer();
        String token = testData.token(org.userId());
        String venueId = api.post("/organizer/venues", token,
                Map.of("name", "Nhà hát Hoà Bình", "address", "240 Ba Tháng Hai", "city", "TP.HCM"))
                .json().get("id").asString();
        nextSecond();
        Instant startsAt = clock.instant().plus(Duration.ofDays(30));
        Map<String, Object> event = Map.of("venueId", venueId, "title", "Đêm nhạc A", "startsAt", startsAt,
                "saleOpensAt", clock.instant(), "saleClosesAt", startsAt.minus(Duration.ofDays(1)));
        String eventId = api.post("/organizer/events", token, event).json().get("id").asString();
        nextSecond();
        Map<String, Object> renamed = new java.util.HashMap<>(event);
        renamed.put("title", "Đêm nhạc B");
        assertThat(api.put("/organizer/events/" + eventId, token, renamed).status()).isEqualTo(200);
        assertThat(api.put("/organizer/events/" + eventId, token, renamed).status())
                .as("sửa mà không đổi gì").isEqualTo(200);
        nextSecond();
        String typeId = api.post("/organizer/events/" + eventId + "/ticket-types", token,
                Map.of("name", "GA", "kind", "GA", "price", 500000, "quantity", 100)).json().get("id").asString();
        nextSecond();
        UUID staff = testData.userWithEmail("staff-audit@test.local", Role.STAFF, null);
        assertThat(api.post("/organizer/events/" + eventId + "/staff", token,
                Map.of("email", "staff-audit@test.local")).status()).isEqualTo(201);
        api.post("/organizer/events/" + eventId + "/staff", token, Map.of("email", "staff-audit@test.local"));

        List<JsonNode> items = items(adminGet("/admin/audit-logs"));

        assertThat(items).extracting(i -> i.get("action").asString()).containsExactly(
                "STAFF_ASSIGNED", "TICKET_TYPE_CREATED", "EVENT_UPDATED", "EVENT_CREATED", "VENUE_CREATED");
        JsonNode updated = items.get(2);
        assertThat(updated.get("actor").get("type").asString()).isEqualTo("USER");
        assertThat(updated.get("actor").get("id").asString()).isEqualTo(org.userId().toString());
        assertThat(updated.get("actor").get("role").asString()).isEqualTo("ORGANIZER");
        assertThat(updated.get("actor").get("email").asString()).isEqualTo(org.email());
        assertThat(updated.get("organizerId").asString()).isEqualTo(org.organizerId().toString());
        assertThat(updated.get("eventId").asString()).isEqualTo(eventId);
        assertThat(updated.get("details").get("changes").get("title").get("from").asString()).isEqualTo("Đêm nhạc A");
        assertThat(updated.get("details").get("changes").get("title").get("to").asString()).isEqualTo("Đêm nhạc B");
        assertThat(updated.get("details").get("changes").has("startsAt")).isFalse();
        assertThat(items.get(1).get("targetId").asString()).isEqualTo(typeId);
        assertThat(items.getFirst().get("targetType").asString()).isEqualTo("USER");
        assertThat(items.getFirst().get("targetId").asString()).isEqualTo(staff.toString());
    }

    @Test
    void price_change_during_sale_is_recorded_and_only_new_orders_pay_the_new_price() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID before = testData.placeOrder(testData.user(), CreateOrderRequest.ga(event.id(), ga, 1)).order().getId();

        Api.Response changed = api.put("/organizer/events/" + event.id() + "/ticket-types/" + ga + "/price",
                testData.token(event.organizer().userId()), Map.of("price", 950000));
        UUID after = testData.placeOrder(testData.user(), CreateOrderRequest.ga(event.id(), ga, 1)).order().getId();

        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.json().get("price").decimalValue()).isEqualByComparingTo("950000");
        assertThat(unitPrice(before)).isEqualByComparingTo("800000");
        assertThat(unitPrice(after)).isEqualByComparingTo("950000");
        JsonNode entry = items(adminGet("/admin/audit-logs?action=TICKET_TYPE_PRICE_CHANGED")).getFirst();
        assertThat(entry.get("details").get("from").decimalValue()).isEqualByComparingTo("800000");
        assertThat(entry.get("details").get("to").decimalValue()).isEqualByComparingTo("950000");
        assertThat(entry.get("actor").get("id").asString()).isEqualTo(event.organizer().userId().toString());
    }

    @Test
    void another_organizer_cannot_change_the_price_and_nothing_is_recorded() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);

        Api.Response response = api.put("/organizer/events/" + event.id() + "/ticket-types/" + ga + "/price",
                testData.token(testData.organizer().userId()), Map.of("price", 1));

        assertThat(response.status()).isEqualTo(403);
        assertThat(count()).isZero();
    }

    @Test
    void downloading_the_attendee_list_is_recorded_with_its_row_count() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        testData.issuedTickets(testData.user(), CreateOrderRequest.ga(event.id(), ga, 3));

        assertThat(api.getBytes("/organizer/events/" + event.id() + "/attendees.csv",
                testData.token(event.organizer().userId())).getStatusCode().value()).isEqualTo(200);

        JsonNode entry = items(adminGet("/admin/audit-logs?action=ATTENDEES_EXPORTED")).getFirst();
        assertThat(entry.get("details").get("rows").asInt()).isEqualTo(3);
        assertThat(entry.get("details").get("complete").asBoolean()).isTrue();
        assertThat(entry.get("organizerId").asString()).isEqualTo(event.organizer().organizerId().toString());
    }

    @Test
    void refunds_made_by_the_system_are_recorded_with_a_system_actor() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        UUID buyer = testData.user();
        UUID orderId = testData.placeOrder(buyer, CreateOrderRequest.ga(event.id(), ga, 2)).order().getId();
        Payment payment = testData.initiatePayment(buyer, orderId);
        clock.advance(Duration.ofMinutes(17));
        expiryJob.sweep();
        testData.sendWebhook(testData.succeededWebhook(payment));
        nextSecond();
        refundJob.run();

        List<JsonNode> items = items(adminGet("/admin/audit-logs"));

        assertThat(items).extracting(i -> i.get("action").asString())
                .containsExactly("PAYMENT_REFUNDED", "PAYMENT_REFUND_REQUESTED");
        assertThat(items.get(0).get("actor").get("name").asString()).isEqualTo("refund-job");
        assertThat(items.get(1).get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(items.get(1).get("actor").get("name").asString()).isEqualTo("payments");
        assertThat(items.get(1).get("details").get("reason").asString()).isEqualTo("ORDER_EXPIRED");
        assertThat(items.get(1).get("details").get("source").asString()).startsWith("evt_");
        assertThat(items.get(1).get("eventId").asString()).isEqualTo(event.id().toString());
    }

    @Test
    void audit_rows_cannot_be_changed_or_deleted() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        api.put("/organizer/events/" + event.id() + "/ticket-types/" + ga + "/price",
                testData.token(event.organizer().userId()), Map.of("price", 900000));
        assertThat(count()).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET action = 'EVENT_CREATED'"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chỉ được ghi thêm");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("chỉ được ghi thêm");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void recording_outside_a_transaction_is_refused() {
        assertThatThrownBy(() -> auditLog.record(AuditEntry.of(AuditAction.EVENT_PUBLISHED, UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count()).isZero();
    }

    @Test
    void only_admins_can_read_the_history() {
        assertThat(api.get("/admin/audit-logs", testData.token(testData.organizer().userId())).status())
                .isEqualTo(403);
        assertThat(api.get("/admin/audit-logs", testData.token(testData.user())).status()).isEqualTo(403);
        assertThat(api.get("/admin/audit-logs", null).status()).isEqualTo(401);
    }

    @Test
    void history_pages_with_a_cursor_and_filters_by_organizer() {
        var first = testData.eventOnSale(e -> {});
        var second = testData.eventOnSale(e -> {});
        UUID a = testData.gaTicketType(first, new BigDecimal("100000"), 10);
        UUID b = testData.gaTicketType(second, new BigDecimal("100000"), 10);
        for (int price = 1; price <= 3; price++) {
            changePrice(first, a, 100000 + price);
            changePrice(second, b, 200000 + price);
            clock.advance(Duration.ofSeconds(1));
        }

        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = adminGet("/admin/audit-logs?limit=4" + (cursor == null ? "" : "&cursor=" + cursor)).json();
            page.get("items").forEach(item -> assertThat(seen.add(item.get("id").asString())).isTrue());
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            pages++;
        } while (cursor != null);
        List<JsonNode> onlyFirst = items(adminGet("/admin/audit-logs?organizerId=" + first.organizer().organizerId()));

        assertThat(seen).hasSize(6);
        assertThat(pages).isEqualTo(2);
        assertThat(onlyFirst).hasSize(3)
                .allSatisfy(i -> assertThat(i.get("eventId").asString()).isEqualTo(first.id().toString()));
        assertThat(onlyFirst.getFirst().get("details").get("to").asInt()).isEqualTo(100003);
        assertThat(adminGet("/admin/audit-logs?cursor=khong-hop-le!").status()).isEqualTo(400);
        assertThat(adminGet("/admin/audit-logs?action=KHONG_CO").status()).isEqualTo(400);
    }

    @Test
    void monthly_partitions_are_kept_ahead() {
        clock.set(Instant.parse("2027-05-15T00:00:00Z"));

        assertThat(partitions.ensureAhead()).isPositive();
        assertThat(partitions.ensureAhead()).as("chạy lại không tạo thêm").isZero();
        YearMonth last = YearMonth.now(clock.withZone(ZoneOffset.UTC)).plusMonths(3);
        assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class,
                "audit_logs_%04d_%02d".formatted(last.getYear(), last.getMonthValue()))).isTrue();
    }

    /** Mỗi thao tác một giây khác nhau, để thứ tự "mới nhất trước" không phụ thuộc id ngẫu nhiên. */
    private void nextSecond() {
        clock.advance(Duration.ofSeconds(1));
    }

    private void changePrice(com.ticket2h.support.TestData.EventRef event, UUID ticketType, int price) {
        assertThat(api.put("/organizer/events/" + event.id() + "/ticket-types/" + ticketType + "/price",
                testData.token(event.organizer().userId()), Map.of("price", price)).status()).isEqualTo(200);
    }

    private Api.Response adminGet(String path) {
        return api.get(path, testData.token(testData.user(Role.ADMIN)));
    }

    private static List<JsonNode> items(Api.Response response) {
        assertThat(response.status()).isEqualTo(200);
        List<JsonNode> items = new ArrayList<>();
        response.json().get("items").forEach(items::add);
        return items;
    }

    private BigDecimal unitPrice(UUID orderId) {
        return jdbc.queryForObject("SELECT unit_price FROM order_items WHERE order_id = ?", BigDecimal.class, orderId);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
    }
}
