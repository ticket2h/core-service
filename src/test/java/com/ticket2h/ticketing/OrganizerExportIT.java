package com.ticket2h.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.common.security.Role;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Api;
import com.ticket2h.support.FakeIdentity;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

/** File CSV của ban tổ chức (ADR-017): danh sách khách và lịch sử soát vé. */
class OrganizerExportIT extends IntegrationTest {

    @Test
    void attendee_list_has_one_row_per_ticket_with_holder_name_email_and_check_in() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 100);
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        List<UUID> seats = testData.seats(event, vip, 2);
        UUID buyer = named("an@test.local", "Nguyễn Văn An");
        List<UUID> tickets = testData.issuedTickets(buyer, CreateOrderRequest.of(event.id(),
                CreateOrderRequest.Item.ga(ga, 1), CreateOrderRequest.Item.seats(vip, List.of(seats.get(1)))));
        UUID staff = testData.assignedStaff(event);
        clock.set(Instant.parse("2026-11-01T13:05:09Z"));   // 20:05:09 giờ Việt Nam
        UUID gaTicket = jdbc.queryForObject("SELECT id FROM tickets WHERE id IN (?, ?) AND seat_id IS NULL",
                UUID.class, tickets.get(0), tickets.get(1));
        assertThat(api.post("/checkin", testData.token(staff),
                Map.of("qrToken", testData.qrToken(gaTicket), "gate", "Cổng 2")).status()).isEqualTo(200);

        Api.Response response = attendees(event);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).startsWith("text/csv");
        assertThat(response.header("Content-Disposition")).contains("attachment").contains(".csv");
        assertThat(response.header("Cache-Control")).contains("no-store");
        assertThat(response.body()).startsWith("﻿");
        List<String> lines = lines(response);
        assertThat(lines.getFirst()).isEqualTo("﻿Mã vé,Loại vé,Ghế,Trạng thái,Họ tên,Email,Vào cổng lúc,Cổng");
        assertThat(lines).hasSize(3);
        String gaLine = lines.stream().filter(l -> l.startsWith(gaTicket.toString())).findFirst().orElseThrow();
        assertThat(gaLine).endsWith(",,Đã vào cổng,Nguyễn Văn An,an@test.local,01/11/2026 20:05:09,Cổng 2")
                .contains(",GA ");
        String vipLine = lines.stream().filter(l -> !l.startsWith(gaTicket.toString()) && !l.startsWith("﻿"))
                .findFirst().orElseThrow();
        assertThat(vipLine).contains(",S R").contains("-2,Chưa vào cổng,Nguyễn Văn An,an@test.local,,");
    }

    @Test
    void cells_are_quoted_and_spreadsheet_formulas_are_neutralized() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 100);
        UUID formula = named("x@test.local", "=HYPERLINK(\"http://evil\",\"Bấm\")");
        UUID comma = named("y@test.local", "Trần, Thị \"Bé\"");
        testData.issuedTickets(formula, CreateOrderRequest.ga(event.id(), ga, 1));
        testData.issuedTickets(comma, CreateOrderRequest.ga(event.id(), ga, 1));

        String body = attendees(event).body();

        assertThat(body).contains(",\"'=HYPERLINK(\"\"http://evil\"\",\"\"Bấm\"\")\",x@test.local,");
        assertThat(body).contains(",\"Trần, Thị \"\"Bé\"\"\",y@test.local,");
    }

    @Test
    void holders_whose_account_was_deleted_are_still_listed() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 100);
        UUID buyer = testData.user();
        testData.issuedTickets(buyer, CreateOrderRequest.ga(event.id(), ga, 1));
        identity.remove(buyer);

        List<String> lines = lines(attendees(event));

        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).contains(",(tài khoản không còn),,");
    }

    /** 2.500 vé: đọc 3 lô (1.000, 1.000, 500), mỗi lô hỏi identity đúng một lần. */
    @Test
    void large_lists_are_read_in_batches_of_1000_with_one_identity_call_per_batch() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 100_000);
        bulkData.paidGaOrders(event.id(), ga, new BigDecimal("500000"), 1_250, 2, START, Duration.ofSeconds(1));
        assertThat(bulkData.issueTickets(event.id())).isEqualTo(2_500);
        identity.synthesizeUnknownAccounts(true);
        int before = identity.batches();

        List<String> lines = lines(attendees(event));

        assertThat(lines).hasSize(2_501);
        assertThat(identity.batches() - before).isEqualTo(3);
        assertThat(lines.subList(1, lines.size())).allMatch(line -> line.contains(",Khách "));
        List<String> ids = lines.subList(1, lines.size()).stream().map(l -> l.substring(0, 36)).toList();
        assertThat(ids).doesNotHaveDuplicates();
        // Đúng thứ tự phát vé: so với database
        assertThat(ids).isEqualTo(jdbc.queryForList(
                "SELECT id::text FROM tickets WHERE event_id = ? ORDER BY issued_at, id", String.class, event.id()));
    }

    @Test
    void identity_down_before_the_first_row_is_a_502_problem_not_a_broken_file() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 100);
        testData.issuedTickets(testData.user(), CreateOrderRequest.ga(event.id(), ga, 1));
        identity.failNext(2);   // lần đầu và lần thử lại

        Api.Response response = attendees(event);

        assertThat(response.status()).isEqualTo(502);
        assertThat(response.code()).isEqualTo("IDENTITY_UNAVAILABLE");
        assertThat(response.header("Content-Type")).contains("json");
    }

    @Test
    void a_short_identity_hiccup_is_retried_once() {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 100);
        testData.issuedTickets(testData.user(), CreateOrderRequest.ga(event.id(), ga, 1));
        identity.failNext(1);

        Api.Response response = attendees(event);

        assertThat(response.status()).isEqualTo(200);
        assertThat(lines(response)).hasSize(2);
    }

    @Test
    void only_the_owning_organizer_can_export() {
        EventRef event = testData.eventOnSale(e -> {});
        String otherOrganizer = testData.token(testData.organizer().userId());
        String customer = testData.token(testData.user());

        for (String path : List.of("/attendees.csv", "/checkins.csv")) {
            Api.Response forbidden = api.get("/organizer/events/" + event.id() + path, otherOrganizer);
            assertThat(forbidden.status()).as(path).isEqualTo(403);
            assertThat(forbidden.code()).as(path).isEqualTo("FORBIDDEN");
            assertThat(api.get("/organizer/events/" + event.id() + path, customer).status()).as(path).isEqualTo(403);
            assertThat(api.get("/organizer/events/" + UUID.randomUUID() + path,
                    testData.token(event.organizer().userId())).status()).as(path).isEqualTo(404);
        }
    }

    @Test
    void check_in_history_lists_scans_in_the_requested_days_with_staff_names() {
        EventRef event = testData.eventOnSale(e -> e.startsAt(Instant.parse("2026-11-08T12:00:00Z")));
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 100);
        List<UUID> tickets = testData.issuedTickets(testData.user(), CreateOrderRequest.ga(event.id(), ga, 2));
        UUID staff = named("soatve@test.local", "Lê Soát Vé", Role.STAFF);
        jdbc.update("INSERT INTO event_staff (event_id, user_id) VALUES (?, ?)", event.id(), staff);
        clock.set(Instant.parse("2026-11-08T11:00:00Z"));   // 18:00 ngày diễn
        String staffToken = testData.token(staff);          // token sống 1 ngày theo clock, nên ký sau khi đặt giờ
        String qr = testData.qrToken(tickets.get(0));
        assertThat(api.post("/checkin", staffToken, Map.of("qrToken", qr, "gate", "A")).status()).isEqualTo(200);
        clock.set(Instant.parse("2026-11-08T11:01:00Z"));
        api.post("/checkin", staffToken, Map.of("qrToken", qr, "gate", "B"));   // quét lại: ALREADY_USED
        // Lượt quét ngoài khoảng ngày (cách xa ngày diễn) không có trong file mặc định
        bulkData.checkinLogs(event.id(), staff, 3, Instant.parse("2026-12-20T00:00:00Z"), Duration.ofMinutes(1));

        String organizer = testData.token(event.organizer().userId());
        List<String> lines = lines(api.get("/organizer/events/" + event.id() + "/checkins.csv", organizer));

        assertThat(lines).hasSize(3);
        assertThat(lines.getFirst()).isEqualTo("﻿Thời gian,Cổng,Kết quả,Mã vé,Nhân viên,Email nhân viên");
        assertThat(lines.get(1)).isEqualTo("08/11/2026 18:00:00,A,Hợp lệ," + tickets.get(0)
                + ",Lê Soát Vé,soatve@test.local");
        assertThat(lines.get(2)).startsWith("08/11/2026 18:01:00,B,Vé đã dùng,");

        List<String> december = lines(api.get("/organizer/events/" + event.id()
                + "/checkins.csv?from=2026-12-20&to=2026-12-20", organizer));
        assertThat(december).hasSize(4);
    }

    @Test
    void check_in_history_range_is_validated() {
        EventRef event = testData.eventOnSale(e -> {});
        String token = testData.token(event.organizer().userId());

        for (String query : List.of("from=2026-11-05&to=2026-11-01", "from=2026-11-01&to=2026-12-02",
                "from=hom-nay")) {
            Api.Response response = api.get("/organizer/events/" + event.id() + "/checkins.csv?" + query, token);
            assertThat(response.status()).as(query).isEqualTo(400);
            assertThat(response.code()).as(query).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(api.get("/organizer/events/" + event.id() + "/checkins.csv?from=2026-11-01&to=2026-12-01", token)
                .status()).isEqualTo(200);
    }

    private Api.Response attendees(EventRef event) {
        return api.get("/organizer/events/" + event.id() + "/attendees.csv",
                testData.token(event.organizer().userId()));
    }

    private UUID named(String email, String fullName) {
        return named(email, fullName, Role.CUSTOMER);
    }

    private UUID named(String email, String fullName, Role role) {
        UUID id = UUID.randomUUID();
        identity.add(new FakeIdentity.Account(id, email, fullName, role, null));
        return id;
    }

    private static List<String> lines(Api.Response response) {
        assertThat(response.status()).isEqualTo(200);
        List<String> lines = new ArrayList<>(List.of(response.body().split("\r\n")));
        return lines;
    }
}
