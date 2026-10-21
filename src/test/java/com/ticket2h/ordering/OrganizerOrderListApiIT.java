package com.ticket2h.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.support.Api;
import com.ticket2h.support.FakeIdentity;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

import tools.jackson.databind.JsonNode;

/** GET /organizer/events/{id}/orders: danh sách đơn cho ban tổ chức, phân trang keyset (ADR-018). */
class OrganizerOrderListApiIT extends IntegrationTest {

    @Test
    void newest_orders_first_with_buyer_name_tickets_and_channel() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 100);
        UUID buyer = UUID.randomUUID();
        identity.add(new FakeIdentity.Account(buyer, "an@test.local", "Nguyễn Văn An",
                com.ticket2h.common.security.Role.CUSTOMER, null));
        UUID first = testData.paidOrderViaWebhook(buyer, CreateOrderRequest.ga(event.id(), ga, 2));
        clock.advance(Duration.ofMinutes(1));
        UUID second = testData.placeOrder(testData.user(), CreateOrderRequest.ga(event.id(), ga, 1)
                .withChannel(SalesChannel.MOBILE_APP)).order().getId();

        Api.Response response = page(event, "");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().propertyNames()).containsExactlyInAnyOrder("items", "limit", "nextCursor");
        assertThat(response.json().get("nextCursor").isNull()).isTrue();
        JsonNode items = response.json().get("items");
        assertThat(ids(items)).containsExactly(second.toString(), first.toString());
        JsonNode paid = items.get(1);
        assertThat(paid.get("status").asString()).isEqualTo("PAID");
        assertThat(paid.get("salesChannel").asString()).isEqualTo("WEB");
        assertThat(paid.get("tickets").asInt()).isEqualTo(2);
        assertThat(paid.get("totalAmount").decimalValue()).isEqualByComparingTo("1000000");
        assertThat(paid.get("buyer").get("fullName").asString()).isEqualTo("Nguyễn Văn An");
        assertThat(paid.get("buyer").get("email").asString()).isEqualTo("an@test.local");
        assertThat(items.get(0).get("salesChannel").asString()).isEqualTo("MOBILE_APP");
        assertThat(items.get(0).get("paidAt").isNull()).isTrue();
        assertThat(page(event, "status=PAID").json().get("items")).hasSize(1);
    }

    /** 45 đơn tạo cùng một thời điểm: phân biệt bằng id, đi hết 3 trang không lặp không sót. */
    @Test
    void walking_next_cursors_visits_every_order_once_even_with_identical_timestamps() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 1_000);
        bulkData.paidGaOrders(event.id(), ga, new BigDecimal("500000"), 45, 1, START, Duration.ZERO);
        identity.synthesizeUnknownAccounts(true);

        List<String> walked = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            Api.Response page = page(event, "limit=20" + (cursor == null ? "" : "&cursor=" + cursor));
            assertThat(page.status()).isEqualTo(200);
            walked.addAll(ids(page.json().get("items")));
            cursor = page.json().get("nextCursor").isNull() ? null : page.json().get("nextCursor").asString();
            pages++;
        } while (cursor != null);

        assertThat(pages).isEqualTo(3);
        assertThat(walked).doesNotHaveDuplicates().hasSize(45);
        assertThat(walked).isEqualTo(jdbc.queryForList(
                "SELECT id::text FROM orders WHERE event_id = ? ORDER BY created_at DESC, id DESC", String.class,
                event.id()));
    }

    @Test
    void bad_cursor_or_limit_is_400_and_limit_is_capped_at_100() {
        EventRef event = testData.eventOnSale(e -> {});
        for (String query : List.of("cursor=abc", "cursor=" + java.util.Base64.getUrlEncoder().encodeToString(
                "2026|x".getBytes()), "limit=0", "status=SHIPPED")) {
            Api.Response response = page(event, query);
            assertThat(response.status()).as(query).isEqualTo(400);
            assertThat(response.code()).as(query).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(page(event, "limit=500").json().get("limit").asInt()).isEqualTo(100);
    }

    @Test
    void organizers_only_see_their_own_events() {
        EventRef event = testData.eventOnSale(e -> {});

        assertThat(api.get("/organizer/events/" + event.id() + "/orders",
                testData.token(testData.organizer().userId())).status()).isEqualTo(403);
        assertThat(api.get("/organizer/events/" + event.id() + "/orders", testData.token(testData.user())).status())
                .isEqualTo(403);
    }

    private Api.Response page(EventRef event, String query) {
        return api.get("/organizer/events/" + event.id() + "/orders?" + query,
                testData.token(event.organizer().userId()));
    }

    private static List<String> ids(JsonNode items) {
        List<String> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.get("orderId").asString()));
        return ids;
    }
}
