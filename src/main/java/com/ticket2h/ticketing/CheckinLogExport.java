package com.ticket2h.ticketing;

import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.catalog.EventSnapshot;
import com.ticket2h.common.csv.CsvWriter;
import com.ticket2h.common.error.ValidationException;
import com.ticket2h.identity.UserDirectory;
import com.ticket2h.identity.UserDirectory.UserContact;

/**
 * File CSV lịch sử soát vé của một sự kiện trong một khoảng ngày (ADR-017, ADR-019). Khác danh sách khách: tên nhân
 * viên hỏi identity một lần TRƯỚC khi đọc (nhân viên chỉ vài chục người), nên phần đọc không gọi mạng và chạy được
 * trong một transaction chỉ đọc, đọc theo luồng bằng cursor của database (fetch size). Đánh đổi: client tải chậm thì
 * transaction mở lâu theo; khoảng ngày bị giới hạn {@value #MAX_DAYS} ngày để file không quá lớn.
 */
@Service
public class CheckinLogExport {

    static final int MAX_DAYS = 31;
    static final List<String> HEADER = List.of("Thời gian", "Cổng", "Kết quả", "Mã vé", "Nhân viên", "Email nhân viên");

    private static final Logger log = LoggerFactory.getLogger(CheckinLogExport.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final CatalogQueries catalog;
    private final CheckinLogStore logs;
    private final UserDirectory users;
    private final CheckinLogReader reader;

    CheckinLogExport(CatalogQueries catalog, CheckinLogStore logs, UserDirectory users, CheckinLogReader reader) {
        this.catalog = catalog;
        this.logs = logs;
        this.users = users;
        this.reader = reader;
    }

    /**
     * {@code from}, {@code to}: ngày theo múi giờ của sự kiện, tính cả hai đầu; null thì từ một ngày trước tới một
     * ngày sau ngày diễn. Trả số dòng đã ghi.
     */
    public int export(UUID organizerId, UUID eventId, LocalDate from, LocalDate to, Supplier<Writer> output) {
        EventSnapshot event = catalog.ownedEvent(organizerId, eventId);
        ZoneId zone = event.timeZone();
        LocalDate eventDay = event.startsAt().atZone(zone).toLocalDate();
        LocalDate first = from != null ? from : eventDay.minusDays(1);
        LocalDate last = to != null ? to : eventDay.plusDays(1);
        if (first.isAfter(last)) {
            throw new ValidationException("Ngày bắt đầu (from) phải trước hoặc bằng ngày kết thúc (to)");
        }
        if (ChronoUnit.DAYS.between(first, last) + 1 > MAX_DAYS) {
            throw new ValidationException("Mỗi lần xuất tối đa " + MAX_DAYS + " ngày");
        }
        Instant start = first.atStartOfDay(zone).toInstant();
        Instant end = last.plusDays(1).atStartOfDay(zone).toInstant();

        List<UUID> staffIds = logs.staffIn(eventId, start, end);
        Map<UUID, UserContact> staff = staffIds.isEmpty() ? Map.of() : users.findByIds(staffIds);   // trước transaction

        CsvWriter csv = new CsvWriter(output.get()).bom().row(HEADER);
        AtomicInteger written = new AtomicInteger();
        try {
            reader.read(eventId, start, end, row -> {
                UserContact who = staff.get(row.staffUserId());
                csv.row(Arrays.asList(TIME.format(row.scannedAt().atZone(zone)), row.gate(), label(row.result()),
                        row.ticketId() == null ? null : row.ticketId().toString(),
                        who == null ? row.staffUserId().toString() : who.fullName(),
                        who == null ? null : who.email()));
                if (written.incrementAndGet() % CheckinLogStore.FETCH_SIZE == 0) {
                    csv.flush();
                }
            });
            csv.flush();
        } catch (UncheckedIOException e) {
            log.warn("Ngừng xuất lịch sử soát vé sự kiện {} sau {} dòng: không ghi được ra client ({})", eventId,
                    written.get(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("Lỗi khi xuất lịch sử soát vé sự kiện {} sau {} dòng", eventId, written.get(), e);
            try {
                csv.comment("LỖI: file chưa đầy đủ do lỗi hệ thống giữa chừng, hãy tải lại").flush();
            } catch (UncheckedIOException ignored) {
                // client đã ngắt kết nối
            }
        }
        return written.get();
    }

    static String label(CheckinResult result) {
        return switch (result) {
            case OK -> "Hợp lệ";
            case ALREADY_USED -> "Vé đã dùng";
            case TOKEN_REVOKED -> "QR cũ (vé đã chuyển nhượng)";
            case VOIDED -> "Vé đã huỷ";
            case WRONG_EVENT_STAFF -> "Nhân viên không được phân công";
            case INVALID_TOKEN -> "QR không hợp lệ";
        };
    }
}
