package com.ticket2h.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

import tools.jackson.databind.JsonNode;

/** POST /organizer/events/{id}/seats/import: định dạng file, từng loại lỗi, quyền, trạng thái sự kiện (ADR-020). */
class SeatImportApiIT extends IntegrationTest {

    @Test
    void excel_style_file_with_bom_crlf_vietnamese_header_and_quotes_is_imported() {
        EventRef event = testData.eventOnSale(e -> e.draft());
        UUID vip = seated(event, "VIP");
        String csv = "﻿Loại vé,Khu,Hàng,Số ghế\r\n"
                + "VIP,\"Khu A, tầng 2\",A,1\r\n"
                + "vip,\"Khu A, tầng 2\",A,2\r\n"     // tên loại vé không phân biệt hoa thường
                + "\r\n"                               // dòng trống bỏ qua
                + " VIP , Khu B , B , 7 \r\n";

        Api.Response response = upload(event, csv);

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("created").asInt()).isEqualTo(3);
        assertThat(response.json().get("ticketTypes").get(0).get("ticketType").asString()).isEqualTo("VIP");
        assertThat(response.json().get("ticketTypes").get(0).get("created").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForList("""
                SELECT section || '|' || row_label || '|' || seat_number FROM seats
                WHERE ticket_type_id = ? ORDER BY section, seat_number
                """, String.class, vip)).containsExactly("Khu A, tầng 2|A|1", "Khu A, tầng 2|A|2", "Khu B|B|7");
    }

    @Test
    void each_kind_of_bad_line_gets_its_own_message() {
        EventRef event = testData.eventOnSale(e -> e.draft());
        seated(event, "VIP");
        testData.gaTicketType(event, new BigDecimal("300000"), 100);
        String gaName = jdbc.queryForObject("SELECT name FROM ticket_types WHERE event_id = ? AND kind = 'GA'",
                String.class, event.id());
        String csv = String.join("\n",
                "loai_ve,khu,hang,so_ghe",
                "VIP,A,1,1",                       // 2: hợp lệ
                "VIP,A,1",                         // 3: thiếu cột
                ",A,1,2",                          // 4: thiếu loại vé
                "VIP,A,1,0",                       // 5: số ghế 0
                "VIP,A,1,abc",                     // 6: không phải số
                "VIP," + "K".repeat(51) + ",1,3",  // 7: khu quá dài
                "Không có,A,1,4",                  // 8: loại vé lạ
                gaName + ",A,1,5",                 // 9: vé đứng không có ghế
                "VIP,A,1,1",                       // 10: trùng dòng 2
                "VIP,A,1,9,thừa");                 // 11: thừa cột

        Api.Response response = upload(event, csv);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("SEAT_IMPORT_INVALID");
        assertThat(response.json().get("errorCount").asInt()).isEqualTo(9);
        List<String> errors = errors(response);
        assertThat(errors).containsExactly(
                "3: Cần 4 cột (loai_ve,khu,hang,so_ghe), dòng này có 3 cột",
                "4: Thiếu loại vé",
                "5: Số ghế \"0\" phải là số nguyên từ 1 đến 99999",
                "6: Số ghế \"abc\" phải là số nguyên từ 1 đến 99999",
                "7: Khu dài quá 50 ký tự",
                "8: Không có loại vé \"Không có\" trong sự kiện",
                "9: Loại vé \"" + gaName + "\" là vé đứng (GA), không có ghế",
                "10: Trùng ghế với dòng 2",
                "11: Cần 4 cột (loai_ve,khu,hang,so_ghe), dòng này có 5 cột");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats", Integer.class)).isZero();
    }

    @Test
    void seats_that_already_exist_are_reported_and_a_second_identical_upload_adds_nothing() {
        EventRef event = testData.eventOnSale(e -> e.draft());
        seated(event, "VIP");
        String csv = "loai_ve,khu,hang,so_ghe\nVIP,A,1,1\nVIP,A,1,2\n";
        assertThat(upload(event, csv).status()).isEqualTo(201);

        Api.Response again = upload(event, csv);

        assertThat(again.status()).isEqualTo(422);
        assertThat(errors(again)).containsExactly("2: Ghế đã có trong sơ đồ", "3: Ghế đã có trong sơ đồ");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats", Integer.class)).isEqualTo(2);
    }

    @Test
    void wrong_header_empty_file_and_broken_quotes_are_reported_on_their_line() {
        EventRef event = testData.eventOnSale(e -> e.draft());
        seated(event, "VIP");

        assertThat(errors(upload(event, "type,section,row,seat\nVIP,A,1,1\n")))
                .containsExactly("1: Dòng tiêu đề phải là \"loai_ve,khu,hang,so_ghe\"");
        assertThat(errors(upload(event, ""))).containsExactly("1: File trống");
        assertThat(errors(upload(event, "loai_ve,khu,hang,so_ghe\n"))).containsExactly("2: File không có ghế nào");
        assertThat(errors(upload(event, "loai_ve,khu,hang,so_ghe\nVIP,A,1,1\nVIP,\"A,1,2\n")))
                .containsExactly("3: Thiếu dấu \" đóng ngoặc");
    }

    @Test
    void file_not_saved_as_utf8_is_rejected_with_a_hint() {
        EventRef event = testData.eventOnSale(e -> e.draft());
        seated(event, "VIP");
        byte[] latin = "loai_ve,khu,hang,so_ghe\nVIP,Khu ".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[latin.length + 4];
        System.arraycopy(latin, 0, body, 0, latin.length);
        body[latin.length] = (byte) 0xC2;   // "Â" của Windows-1258 + byte không hợp lệ theo UTF-8
        body[latin.length + 1] = ',';
        body[latin.length + 2] = '1';
        body[latin.length + 3] = '\n';

        Api.Response response = api.postBytes(path(event), token(event), body, "text/csv");

        assertThat(response.status()).isEqualTo(422);
        assertThat(errors(response).getFirst()).contains("UTF-8");
    }

    @Test
    void only_the_first_200_errors_are_listed_but_all_are_counted() {
        EventRef event = testData.eventOnSale(e -> e.draft());
        seated(event, "VIP");
        StringBuilder csv = new StringBuilder("loai_ve,khu,hang,so_ghe\n");
        for (int i = 0; i < 500; i++) {
            csv.append("VIP,A,1,x\n");
        }

        Api.Response response = upload(event, csv.toString());

        assertThat(response.json().get("errorCount").asInt()).isEqualTo(500);
        assertThat(response.json().get("errors")).hasSize(200);
        assertThat(response.json().get("errors").get(199).get("line").asInt()).isEqualTo(201);
    }

    @Test
    void files_over_5mb_are_413() {
        EventRef event = testData.eventOnSale(e -> e.draft());
        seated(event, "VIP");
        // 95.000 dòng (dưới giới hạn 100.000 ghế) nhưng mỗi dòng 59 byte: hơn 5MB
        String big = "loai_ve,khu,hang,so_ghe\n" + ("VIP," + "K".repeat(50) + ",A,1\n").repeat(95_000);

        Api.Response response = upload(event, big);

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.code()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void published_events_and_other_organizers_are_rejected_before_reading_the_file() {
        EventRef published = testData.eventOnSale(e -> {});
        seated(published, "VIP");
        EventRef draft = testData.eventOnSale(e -> e.draft());
        String csv = "loai_ve,khu,hang,so_ghe\nVIP,A,1,1\n";

        Api.Response notEditable = upload(published, csv);
        Api.Response otherOrganizer = api.postBytes(path(draft),
                testData.token(testData.organizer().userId()), "rác".getBytes(StandardCharsets.UTF_8), "text/csv");

        assertThat(notEditable.status()).isEqualTo(409);
        assertThat(notEditable.code()).isEqualTo("EVENT_NOT_EDITABLE");
        assertThat(otherOrganizer.status()).isEqualTo(403);
        assertThat(api.postBytes(path(draft), token(draft), csv.getBytes(StandardCharsets.UTF_8), "image/png")
                .status()).isEqualTo(415);
    }

    private UUID seated(EventRef event, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ticket_types (id, event_id, name, kind, price, created_at)
                VALUES (?, ?, ?, 'SEATED', 1000000, now())
                """, id, event.id(), name);
        return id;
    }

    private Api.Response upload(EventRef event, String csv) {
        return api.postBytes(path(event), token(event), csv.getBytes(StandardCharsets.UTF_8), "text/csv");
    }

    private static String path(EventRef event) {
        return "/organizer/events/" + event.id() + "/seats/import";
    }

    private String token(EventRef event) {
        return testData.token(event.organizer().userId());
    }

    private static List<String> errors(Api.Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(422);
        List<String> errors = new ArrayList<>();
        for (JsonNode error : response.json().get("errors")) {
            errors.add(error.get("line").asInt() + ": " + error.get("message").asString());
        }
        return errors;
    }
}
