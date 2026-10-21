package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

/**
 * M4: xuất 500.000 dòng với heap 256MB không lỗi (ADR-017). Class này chạy trong một JVM riêng {@code -Xmx256m}
 * (execution {@code export-memory} của maven-failsafe-plugin trong pom.xml). Cả app lẫn test cùng nằm trong 256MB đó:
 * nếu app gom 500.000 dòng vào một {@code List} hay một chuỗi rồi mới trả, JVM hết heap và test đỏ.
 */
class ExportMemoryIT extends IntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(ExportMemoryIT.class);
    private static final int ROWS = 500_000;

    @BeforeAll
    static void runsWithASmallHeap() {
        long maxHeapMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        assertThat(maxHeapMb).as("heap tối đa (MB): chạy bằng ./mvnw verify để có -Xmx256m").isLessThanOrEqualTo(256);
    }

    @Test
    @Tag("slow")
    void attendee_list_of_500k_tickets_streams_within_256mb() throws Exception {
        EventRef event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("500000"), 1_000_000);
        bulkData.paidGaOrders(event.id(), ga, new BigDecimal("500000"), ROWS / 4, 4, START, Duration.ofMillis(10));
        assertThat(bulkData.issueTickets(event.id())).isEqualTo(ROWS);
        identity.synthesizeUnknownAccounts(true);   // 125.000 người mua, không đăng ký trước vào bộ nhớ

        Download download = download("/organizer/events/" + event.id() + "/attendees.csv", event);

        assertThat(download.status()).isEqualTo(200);
        assertThat(download.dataLines()).isEqualTo(ROWS);
        assertThat(download.lastLine()).contains(",Khách ").doesNotStartWith("#");
        assertThat(identity.batches()).isEqualTo(ROWS / 1_000);   // lô rỗng cuối cùng không cần hỏi identity
        log.info("Xuất {} khách: {} ms, heap tối đa {} MB", ROWS, download.millis(),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
    }

    @Test
    @Tag("slow")
    void check_in_history_of_500k_scans_streams_within_256mb() throws Exception {
        EventRef event = testData.eventOnSale(e -> {});
        UUID staff = testData.assignedStaff(event);
        Instant first = event.startsAt().minus(Duration.ofHours(12));
        bulkData.checkinLogs(event.id(), staff, ROWS, first, Duration.ofMillis(100));   // 500.000 lượt trong ~14 giờ
        LocalDate day = first.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate();

        Download download = download("/organizer/events/" + event.id() + "/checkins.csv?from=" + day + "&to="
                + day.plusDays(1), event);

        assertThat(download.status()).isEqualTo(200);
        assertThat(download.dataLines()).isEqualTo(ROWS);
        assertThat(download.lastLine()).contains(",A,Hợp lệ,");
        log.info("Xuất {} lượt soát vé: {} ms", ROWS, download.millis());
    }

    /** Đọc response theo từng dòng, chỉ đếm, không giữ lại: phía test cũng không được tốn bộ nhớ theo số dòng. */
    private Download download(String path, EventRef event) throws Exception {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + testData.token(event.organizer().userId()))
                .timeout(Duration.ofMinutes(5)).GET().build();
        long started = System.nanoTime();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int lines = 0;
        String last = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines++;
                last = line;
            }
        }
        return new Download(response.statusCode(), lines - 1, last, (System.nanoTime() - started) / 1_000_000);
    }

    private record Download(int status, int dataLines, String lastLine, long millis) {}
}
