package com.ticket2h.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.common.security.Role;
import com.ticket2h.support.Api;
import com.ticket2h.support.FakeIdentity;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

import tools.jackson.databind.JsonNode;

/**
 * {@code GET /organizer/events/{id}/staff}: core chỉ lưu id nhân viên, email và tên hỏi identity-service
 * ({@code POST /internal/users/batch}, một lần cho cả danh sách) sau khi đọc xong danh sách, ngoài transaction.
 */
class OrganizerStaffApiIT extends IntegrationTest {

    @Test
    void owner_sees_assigned_staff_with_names_sorted_by_name_then_email() {
        EventRef event = testData.eventOnSale(e -> {});
        String org = testData.token(event.organizer().userId());
        UUID binh = assign(event, org, "binh@test.local", "Bình");
        UUID anhZ = assign(event, org, "z.anh@test.local", "Ánh");
        UUID duc = assign(event, org, "duc@test.local", "Đức");
        UUID anhA = assign(event, org, "a.anh@test.local", "Ánh");
        UUID dung = assign(event, org, "dung@test.local", "Dũng");
        // Nhân viên của sự kiện khác không lẫn vào
        EventRef other = testData.eventOnSale(e -> {});
        assign(other, testData.token(other.organizer().userId()), "khac@test.local", "An");

        int batchesBefore = identity.batches();
        Api.Response response = api.get(staffPath(event), org);

        assertThat(response.status()).isEqualTo(200);
        assertThat(identity.batches() - batchesBefore).as("số lần hỏi identity cho 5 nhân viên").isEqualTo(1);
        List<JsonNode> items = response.json().valueStream().toList();
        // Thứ tự tiếng Việt: Á sau A, trước B; Đ sau D (so chuỗi thường thì "Ánh", "Đức" xếp sau "Z")
        assertThat(items).extracting(i -> i.get("userId").asString())
                .containsExactly(anhA.toString(), anhZ.toString(), binh.toString(), dung.toString(), duc.toString());
        JsonNode first = items.getFirst();
        assertThat(first.propertyNames()).containsExactlyInAnyOrder("userId", "email", "fullName");
        assertThat(first.get("email").asString()).isEqualTo("a.anh@test.local");
        assertThat(first.get("fullName").asString()).isEqualTo("Ánh");
    }

    @Test
    void no_staff_is_an_empty_array() {
        EventRef event = testData.eventOnSale(e -> {});

        Api.Response response = api.get(staffPath(event), testData.token(event.organizer().userId()));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().isArray()).isTrue();
        assertThat(response.json().size()).isZero();
    }

    @Test
    void another_organizer_gets_403_unknown_event_404_customer_403() {
        EventRef event = testData.eventOnSale(e -> {});
        assign(event, testData.token(event.organizer().userId()), "gate@test.local", "Soát Vé");
        String otherOrg = testData.token(testData.organizer().userId());

        Api.Response foreign = api.get(staffPath(event), otherOrg);
        Api.Response unknown = api.get("/organizer/events/" + UUID.randomUUID() + "/staff", otherOrg);
        Api.Response customer = api.get(staffPath(event), testData.token(testData.user()));

        assertThat(foreign.status()).isEqualTo(403);
        assertThat(foreign.code()).isEqualTo("FORBIDDEN");
        assertThat(foreign.body()).doesNotContain("gate@test.local");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("NOT_FOUND");
        assertThat(customer.status()).isEqualTo(403);
    }

    @Test
    void identity_down_is_502_and_changes_nothing() {
        EventRef event = testData.eventOnSale(e -> {});
        String org = testData.token(event.organizer().userId());
        assign(event, org, "gate1@test.local", "Cổng Một");
        assign(event, org, "gate2@test.local", "Cổng Hai");
        identity.failNext(1);

        Api.Response response = api.get(staffPath(event), org);

        assertThat(response.status()).isEqualTo(502);
        assertThat(response.code()).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("gate1@test.local", "gate2@test.local");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_staff WHERE event_id = ?", Integer.class,
                event.id())).isEqualTo(2);

        // identity sống lại: như thường
        Api.Response retry = api.get(staffPath(event), org);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json().size()).isEqualTo(2);
    }

    @Test
    void deleted_account_is_listed_with_null_email_and_name_and_can_be_removed() {
        EventRef event = testData.eventOnSale(e -> {});
        String org = testData.token(event.organizer().userId());
        UUID kept = assign(event, org, "kept@test.local", "Zũng");
        UUID deleted = assign(event, org, "deleted@test.local", "An");
        identity.remove(deleted);

        Api.Response response = api.get(staffPath(event), org);

        assertThat(response.status()).isEqualTo(200);
        List<JsonNode> items = response.json().valueStream().toList();
        // Tài khoản không còn xếp cuối (không có tên để sắp)
        assertThat(items).extracting(i -> i.get("userId").asString())
                .containsExactly(kept.toString(), deleted.toString());
        assertThat(items.get(0).get("fullName").asString()).isEqualTo("Zũng");
        assertThat(items.get(1).has("email")).isTrue();
        assertThat(items.get(1).get("email").isNull()).isTrue();
        assertThat(items.get(1).get("fullName").isNull()).isTrue();

        assertThat(api.delete(staffPath(event) + "/" + deleted, org).status()).isEqualTo(204);
        assertThat(api.get(staffPath(event), org).json().valueStream().map(i -> i.get("userId").asString()))
                .containsExactly(kept.toString());
    }

    /** Tạo tài khoản STAFF ở FakeIdentity rồi phân công qua API như BTC thật. */
    private UUID assign(EventRef event, String organizerToken, String email, String fullName) {
        UUID id = UUID.randomUUID();
        identity.add(new FakeIdentity.Account(id, email, fullName, Role.STAFF, null));
        Api.Response response = api.post(staffPath(event), organizerToken, Map.of("email", email));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return id;
    }

    private static String staffPath(EventRef event) {
        return "/organizer/events/" + event.id() + "/staff";
    }
}
