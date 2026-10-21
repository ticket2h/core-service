package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

import tools.jackson.databind.JsonNode;

/** M4: ban tổ chức nhập sơ đồ 50.000 ghế từ file, dòng nào lỗi thì báo đúng dòng đó (ADR-020). */
class SeatImportIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SeatImportIT.class);
    private static final int SEATS = 50_000;

    @Test
    @Tag("slow")
    void organizer_imports_50k_seats_from_a_csv_file() {
        EventRef event = testData.eventOnSale(e -> e.draft());
        insertSeatedType(event, "VIP");
        insertSeatedType(event, "Thường");

        long started = System.nanoTime();
        Api.Response response = upload(event, file(SEATS, List.of()));
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.json().get("created").asInt()).isEqualTo(SEATS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE event_id = ?", Integer.class, event.id()))
                .isEqualTo(SEATS);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM seats s JOIN ticket_types tt ON tt.id = s.ticket_type_id
                WHERE s.event_id = ? AND tt.name = 'VIP'
                """, Integer.class, event.id())).isEqualTo(10_000);
        log.info("Nhập {} ghế: {} ms", SEATS, millis);
    }

    /** Lỗi ở dòng thứ 2 (ghế đầu tiên), dòng giữa file và dòng CUỐI cùng: báo đúng ba dòng, không thêm ghế nào. */
    @Test
    @Tag("slow")
    void every_bad_line_is_reported_with_its_exact_line_number_and_nothing_is_imported() {
        EventRef event = testData.eventOnSale(e -> e.draft());
        insertSeatedType(event, "VIP");
        insertSeatedType(event, "Thường");
        int last = SEATS + 1;   // dòng 1 là tiêu đề

        Api.Response response = upload(event, file(SEATS, List.of(
                new Broken(2, "VIP,Khu A,A,không-phải-số"),
                new Broken(25_001, "VIP,Khu A,A,2"),           // trùng ghế ở dòng 3 (ghế thứ hai của hàng A)
                new Broken(last, "Hạng Z,Khu B,B,1"))));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("SEAT_IMPORT_INVALID");
        JsonNode errors = response.json().get("errors");
        assertThat(response.json().get("errorCount").asInt()).isEqualTo(3);
        assertThat(errors).hasSize(3);
        assertThat(errors.get(0).get("line").asInt()).isEqualTo(2);
        assertThat(errors.get(0).get("message").asString()).contains("không-phải-số");
        assertThat(errors.get(1).get("line").asInt()).isEqualTo(25_001);
        assertThat(errors.get(1).get("message").asString()).isEqualTo("Trùng ghế với dòng 3");
        assertThat(errors.get(2).get("line").asInt()).isEqualTo(last);
        assertThat(errors.get(2).get("message").asString()).contains("Hạng Z");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE event_id = ?", Integer.class, event.id()))
                .isZero();
    }

    private void insertSeatedType(EventRef event, String name) {
        jdbc.update("""
                INSERT INTO ticket_types (id, event_id, name, kind, price, created_at)
                VALUES (gen_random_uuid(), ?, ?, 'SEATED', ?, now())
                """, event.id(), name, new BigDecimal("1000000"));
    }

    /**
     * {@code seats} ghế: 10.000 ghế VIP (khu "Khu A"), còn lại vé Thường (khu "Khu B"), 50 ghế mỗi hàng. Dòng trong
     * {@code broken} thay cho dòng cùng số (dòng 1 là tiêu đề, ghế thứ n ở dòng n + 1).
     */
    private static byte[] file(int seats, List<Broken> broken) {
        StringBuilder csv = new StringBuilder("loai_ve,khu,hang,so_ghe\r\n");
        List<Broken> replacements = new ArrayList<>(broken);
        for (int n = 0; n < seats; n++) {
            int line = n + 2;
            Broken replacement = replacements.stream().filter(b -> b.line() == line).findFirst().orElse(null);
            if (replacement != null) {
                csv.append(replacement.content()).append("\r\n");
                continue;
            }
            boolean vip = n < 10_000;
            int index = vip ? n : n - 10_000;
            csv.append(vip ? "VIP" : "Thường").append(',').append(vip ? "Khu A" : "Khu B").append(',')
                    .append(rowLabel(index / 50)).append(',').append(index % 50 + 1).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 0 → A, 25 → Z, 26 → AA... */
    private static String rowLabel(int index) {
        StringBuilder label = new StringBuilder();
        int i = index;
        do {
            label.insert(0, (char) ('A' + i % 26));
            i = i / 26 - 1;
        } while (i >= 0);
        return label.toString();
    }

    private Api.Response upload(EventRef event, byte[] body) {
        return api.postBytes("/organizer/events/" + event.id() + "/seats/import",
                testData.token(event.organizer().userId()), body, "text/csv");
    }

    private record Broken(int line, String content) {}
}
