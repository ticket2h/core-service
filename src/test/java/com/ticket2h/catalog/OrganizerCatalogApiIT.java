package com.ticket2h.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ticket2h.common.security.Role;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.OrganizerRef;

/** M1: ban tổ chức tạo sự kiện, địa điểm, loại vé, sơ đồ ghế; tìm kiếm không dấu. */
class OrganizerCatalogApiIT extends IntegrationTest {

    @Test
    void organizer_builds_and_publishes_an_event_that_customers_can_find_and_see() {
        String org = testData.token(testData.organizer().userId());
        String eventId = createEvent(org, "Sơn Tùng M-TP Live Concert");
        String vip = api.post("/organizer/events/" + eventId + "/ticket-types", org,
                Map.of("name", "VIP", "kind", "SEATED", "price", 1500000)).json().get("id").asString();
        Api.Response ga = api.post("/organizer/events/" + eventId + "/ticket-types", org,
                Map.of("name", "GA", "kind", "GA", "price", 800000, "quantity", 500));
        assertThat(ga.status()).isEqualTo(201);
        Api.Response seats = api.post("/organizer/events/" + eventId + "/seats", org, Map.of(
                "ticketTypeId", vip, "section", "VIP",
                "rows", List.of(Map.of("label", "A", "from", 1, "to", 10), Map.of("label", "B", "from", 1, "to", 8))));
        assertThat(seats.status()).isEqualTo(201);
        assertThat(seats.json().get("created").asInt()).isEqualTo(18);

        // Chưa công bố: khách không thấy
        assertThat(api.get("/events/" + eventId, null).status()).isEqualTo(404);
        assertThat(api.get("/events?q=son tung", null).json().get("total").asInt()).isZero();

        Api.Response published = api.post("/organizer/events/" + eventId + "/publish", org, null);
        assertThat(published.json().get("status").asString()).isEqualTo("PUBLISHED");

        Api.Response detail = api.get("/events/" + eventId, null);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(detail.json().get("onSale").asBoolean()).isTrue();
        // Kiểm tra theo tên: trong test đồng hồ đứng yên nên hai loại vé có cùng created_at
        Map<String, Integer> available = new java.util.HashMap<>();
        detail.json().get("ticketTypes").valueStream()
                .forEach(t -> available.put(t.get("name").asString(), t.get("available").asInt()));
        assertThat(available).containsExactlyInAnyOrderEntriesOf(Map.of("VIP", 18, "GA", 500));
        assertThat(api.get("/events/" + eventId + "/seats", null).json().size()).isEqualTo(18);
    }

    @Test
    void searching_without_diacritics_finds_vietnamese_titles() {
        String org = testData.token(testData.organizer().userId());
        String sonTung = publishedGaEvent(org, "Sơn Tùng M-TP Live Concert");
        publishedGaEvent(org, "Đêm nhạc Mỹ Tâm");

        Api.Response result = api.get("/events?q=son tung", null);

        assertThat(result.json().get("total").asInt()).isEqualTo(1);
        assertThat(result.json().get("items").get(0).get("id").asString()).isEqualTo(sonTung);
        assertThat(result.json().get("items").get(0).get("title").asString()).isEqualTo("Sơn Tùng M-TP Live Concert");
        assertThat(api.get("/events?q=SƠN TÙNG", null).json().get("total").asInt()).isEqualTo(1);
        assertThat(api.get("/events?q=tung son", null).json().get("total").asInt()).isEqualTo(1);
        assertThat(api.get("/events?q=my tam", null).json().get("total").asInt()).isEqualTo(1);
        // Tìm theo thành phố của địa điểm cũng được ("Hà Nội" → "ha noi")
        assertThat(api.get("/events?q=ha noi", null).json().get("total").asInt()).isEqualTo(2);
        assertThat(api.get("/events?q=khong ton tai", null).json().get("total").asInt()).isZero();
        assertThat(api.get("/events", null).json().get("total").asInt()).isEqualTo(2);
    }

    @Test
    void organizer_cannot_edit_another_organizers_event() {
        String orgA = testData.token(testData.organizer().userId());
        OrganizerRef b = testData.organizer();
        String orgB = testData.token(b.userId());
        String eventOfA = createEvent(orgA, "Sự kiện của A");
        String venueOfB = createVenue(orgB);

        Api.Response edit = api.put("/organizer/events/" + eventOfA, orgB, eventBody(venueOfB, "Bị sửa"));
        Api.Response addType = api.post("/organizer/events/" + eventOfA + "/ticket-types", orgB,
                Map.of("name", "X", "kind", "GA", "price", 1, "quantity", 1));
        Api.Response publish = api.post("/organizer/events/" + eventOfA + "/publish", orgB, null);
        Api.Response view = api.get("/organizer/events/" + eventOfA, orgB);

        assertThat(List.of(edit.status(), addType.status(), publish.status(), view.status())).containsOnly(403);
        assertThat(edit.code()).isEqualTo("FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT title FROM events", String.class)).isEqualTo("Sự kiện của A");
    }

    @Test
    void customers_cannot_use_organizer_endpoints() {
        Api.Response response = api.post("/organizer/venues", testData.token(testData.user()),
                Map.of("name", "X", "address", "Y", "city", "Z"));

        assertThat(response.status()).isEqualTo(403);
    }

    @Test
    void published_event_is_locked_for_ticket_types_and_publish_requires_seats() {
        String org = testData.token(testData.organizer().userId());
        String eventId = createEvent(org, "Thiếu ghế");
        api.post("/organizer/events/" + eventId + "/ticket-types", org,
                Map.of("name", "VIP", "kind", "SEATED", "price", 100));

        Api.Response noSeats = api.post("/organizer/events/" + eventId + "/publish", org, null);
        assertThat(noSeats.status()).isEqualTo(409);
        assertThat(noSeats.code()).isEqualTo("EVENT_NOT_PUBLISHABLE");

        String other = publishedGaEvent(org, "Đã công bố");
        Api.Response locked = api.post("/organizer/events/" + other + "/ticket-types", org,
                Map.of("name", "Thêm", "kind", "GA", "price", 1, "quantity", 1));
        assertThat(locked.status()).isEqualTo(409);
        assertThat(locked.code()).isEqualTo("EVENT_NOT_EDITABLE");
    }

    @Test
    void invalid_sale_window_and_duplicate_seats_are_rejected() {
        String org = testData.token(testData.organizer().userId());
        String venue = createVenue(org);
        Instant now = clock.instant();
        Api.Response badWindow = api.post("/organizer/events", org, Map.of("venueId", venue, "title", "X",
                "startsAt", now.plus(Duration.ofDays(1)).toString(),
                "saleOpensAt", now.toString(), "saleClosesAt", now.plus(Duration.ofDays(2)).toString()));
        assertThat(badWindow.status()).isEqualTo(400);

        String eventId = createEvent(org, "Ghế trùng");
        String vip = api.post("/organizer/events/" + eventId + "/ticket-types", org,
                Map.of("name", "VIP", "kind", "SEATED", "price", 100)).json().get("id").asString();
        Map<String, Object> rowA = Map.of("ticketTypeId", vip, "section", "S",
                "rows", List.of(Map.of("label", "A", "from", 1, "to", 5)));
        assertThat(api.post("/organizer/events/" + eventId + "/seats", org, rowA).status()).isEqualTo(201);
        assertThat(api.post("/organizer/events/" + eventId + "/seats", org, rowA).code()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void malformed_input_is_400_not_500() {
        String org = testData.token(testData.organizer().userId());
        String eventId = createEvent(org, "Dữ liệu xấu");
        String vip = api.post("/organizer/events/" + eventId + "/ticket-types", org,
                Map.of("name", "VIP", "kind", "SEATED", "price", 100)).json().get("id").asString();

        assertThat(api.post("/organizer/events/" + eventId + "/seats", org,
                "{\"ticketTypeId\":\"" + vip + "\",\"section\":\"S\",\"rows\":[null]}").status()).isEqualTo(400);
        assertThat(api.post("/organizer/events/" + eventId + "/ticket-types", org,
                Map.of("name", "Đắt", "kind", "GA", "price", new java.math.BigDecimal("1e15"), "quantity", 1))
                .status()).isEqualTo(400);
        assertThat(api.post("/organizer/events/" + eventId + "/ticket-types", org,
                Map.of("name", "Âm", "kind", "GA", "price", -1, "quantity", 1)).status()).isEqualTo(400);
        assertThat(api.get("/events?page=200000000&size=50", null).status()).isEqualTo(200);
        assertThat(api.get("/events?page=-5&size=0", null).status()).isEqualTo(200);
    }

    @Test
    void organizer_assigns_staff_by_email() {
        OrganizerRef organizer = testData.organizer();
        String org = testData.token(organizer.userId());
        String eventId = createEvent(org, "Có soát vé");
        testData.userWithEmail("gate@test.local", Role.STAFF, null);
        testData.userWithEmail("customer@test.local", Role.CUSTOMER, null);

        assertThat(api.post("/organizer/events/" + eventId + "/staff", org, Map.of("email", "gate@test.local"))
                .status()).isEqualTo(201);
        assertThat(api.post("/organizer/events/" + eventId + "/staff", org, Map.of("email", "customer@test.local"))
                .status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_staff", Integer.class)).isEqualTo(1);
    }

    private String publishedGaEvent(String org, String title) {
        String eventId = createEvent(org, title);
        api.post("/organizer/events/" + eventId + "/ticket-types", org,
                Map.of("name", "GA", "kind", "GA", "price", 100000, "quantity", 10));
        assertThat(api.post("/organizer/events/" + eventId + "/publish", org, null).status()).isEqualTo(200);
        return eventId;
    }

    private String createEvent(String org, String title) {
        Api.Response response = api.post("/organizer/events", org, eventBody(createVenue(org), title));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json().get("id").asString();
    }

    private String createVenue(String org) {
        Api.Response response = api.post("/organizer/venues", org,
                Map.of("name", "Cung Văn hoá Hữu nghị", "address", "91 Trần Hưng Đạo", "city", "Hà Nội"));
        assertThat(response.status()).isEqualTo(201);
        return response.json().get("id").asString();
    }

    private Map<String, Object> eventBody(String venueId, String title) {
        Instant now = clock.instant();
        return Map.of("venueId", venueId, "title", title,
                "startsAt", now.plus(Duration.ofDays(30)).toString(),
                "saleOpensAt", now.minus(Duration.ofHours(1)).toString(),
                "saleClosesAt", now.plus(Duration.ofDays(29)).toString());
    }
}
