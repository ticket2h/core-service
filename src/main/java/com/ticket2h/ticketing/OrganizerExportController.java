package com.ticket2h.ticketing;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import com.ticket2h.catalog.OrganizerCatalogService;
import com.ticket2h.common.security.AuthUser;

/**
 * File CSV cho ban tổ chức (chỉ vai trò ORGANIZER: SecurityConfig chặn theo /organizer/**). Ghi thẳng vào output
 * stream của response trong luồng xử lý request (không dùng {@code StreamingResponseBody}: chạy ở luồng khác thì có
 * timeout của async request, mặc định 30 giây, file lớn sẽ bị cắt).
 */
@RestController
@RequestMapping("/organizer/events/{eventId}")
class OrganizerExportController {

    private static final int BUFFER = 64 * 1024;

    private final OrganizerCatalogService organizers;
    private final AttendeeExport attendees;
    private final CheckinLogExport checkins;

    OrganizerExportController(OrganizerCatalogService organizers, AttendeeExport attendees, CheckinLogExport checkins) {
        this.organizers = organizers;
        this.attendees = attendees;
        this.checkins = checkins;
    }

    /**
     * Danh sách khách: mỗi vé còn hiệu lực một dòng (Mã vé, Loại vé, Ghế, Trạng thái, Họ tên, Email, Vào cổng lúc,
     * Cổng).
     */
    @GetMapping("/attendees.csv")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv",
            schema = @Schema(type = "string", format = "binary")))
    void attendees(AuthUser caller, @PathVariable UUID eventId, HttpServletResponse response) {
        attendees.export(organizers.organizerOf(caller), eventId,
                csvOutput(response, "danh-sach-khach-" + eventId.toString().substring(0, 8) + ".csv"));
    }

    /** Lịch sử soát vé trong khoảng ngày {@code from}..{@code to} (YYYY-MM-DD theo múi giờ sự kiện, tối đa 31 ngày). */
    @GetMapping("/checkins.csv")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = "text/csv",
            schema = @Schema(type = "string", format = "binary")))
    void checkins(AuthUser caller, @PathVariable UUID eventId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletResponse response) {
        checkins.export(organizers.organizerOf(caller), eventId, from, to,
                csvOutput(response, "lich-su-soat-ve-" + eventId.toString().substring(0, 8) + ".csv"));
    }

    /**
     * Chỉ đặt header và mở stream khi service gọi tới, tức là sau khi đã kiểm tra quyền và chuẩn bị xong lô đầu: lỗi
     * trước đó vẫn trả JSON ProblemDetail với đúng mã HTTP như mọi API khác.
     */
    private static Supplier<Writer> csvOutput(HttpServletResponse response, String filename) {
        return () -> {
            response.setContentType("text/csv;charset=UTF-8");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(filename).build().toString());
            // Có dữ liệu cá nhân (email): không để trình duyệt hay proxy lưu lại
            response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
            try {
                return new BufferedWriter(new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8),
                        BUFFER);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}
