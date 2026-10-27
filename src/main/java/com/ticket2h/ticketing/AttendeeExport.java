package com.ticket2h.ticketing;

import java.io.UncheckedIOException;
import java.io.Writer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ticket2h.audit.AuditAction;
import com.ticket2h.audit.AuditEntry;
import com.ticket2h.audit.AuditLog;
import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.catalog.EventSnapshot;
import com.ticket2h.common.csv.CsvWriter;
import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.identity.UserDirectory;
import com.ticket2h.identity.UserDirectory.UserContact;
import com.ticket2h.ticketing.TicketStore.AttendeeRow;

/**
 * File CSV danh sách khách của một sự kiện (ADR-017): mỗi vé còn hiệu lực một dòng, kèm họ tên và email người giữ vé.
 * Xuất 500.000 dòng mà bộ nhớ không tăng theo số dòng: đọc từng lô {@value #BATCH_SIZE} vé (keyset), hỏi identity tên
 * và email của cả lô trong MỘT lần gọi, ghi lô đó ra response rồi mới đọc lô sau. Trong bộ nhớ chỉ có một lô.
 * <p>
 * KHÔNG có {@code @Transactional}: giữa hai lô là lời gọi HTTP sang identity, không được giữ transaction (và kết nối
 * database) trong lúc chờ mạng. Hệ quả: file không phải ảnh chụp tại một thời điểm, vé chuyển nhượng giữa lúc xuất có
 * thể mang tên chủ mới.
 */
@Service
public class AttendeeExport {

    static final int BATCH_SIZE = 1_000;
    static final List<String> HEADER = List.of("Mã vé", "Loại vé", "Ghế", "Trạng thái", "Họ tên", "Email",
            "Vào cổng lúc", "Cổng");

    private static final Logger log = LoggerFactory.getLogger(AttendeeExport.class);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    private final CatalogQueries catalog;
    private final TicketStore tickets;
    private final UserDirectory users;
    private final AuditLog audit;

    AttendeeExport(CatalogQueries catalog, TicketStore tickets, UserDirectory users, AuditLog audit) {
        this.catalog = catalog;
        this.tickets = tickets;
        this.users = users;
        this.audit = audit;
    }

    /**
     * Kiểm tra quyền và chuẩn bị lô đầu TRƯỚC khi gọi {@code output}: BTC khác nhận 403, identity không trả lời thì
     * nhận 502, như mọi API khác. Sau khi đã ghi dòng đầu thì không đổi được mã HTTP nữa: lỗi giữa chừng được ghi thành
     * một dòng {@code # LỖI} ở cuối file, để người mở file biết file chưa đủ. Trả số dòng đã ghi.
     */
    public int export(UUID organizerId, UUID eventId, Supplier<Writer> output) {
        EventSnapshot event = catalog.ownedEvent(organizerId, eventId);
        long started = System.nanoTime();
        Batch batch = batch(eventId, null);
        CsvWriter csv = new CsvWriter(output.get()).bom().row(HEADER);
        int written = 0;
        try {
            while (true) {
                for (AttendeeRow row : batch.rows()) {
                    csv.row(cells(row, batch.contacts().get(row.ownerUserId()), event.timeZone()));
                    written++;
                }
                csv.flush();
                if (batch.rows().size() < BATCH_SIZE) {
                    break;
                }
                batch = batch(eventId, batch.rows().getLast());
            }
        } catch (UncheckedIOException e) {
            log.warn("Ngừng xuất danh sách khách sự kiện {} sau {} dòng: không ghi được ra client ({})", eventId,
                    written, e.getMessage());
            recordExport(organizerId, eventId, written, false);
            return written;
        } catch (RuntimeException e) {
            log.error("Lỗi khi xuất danh sách khách sự kiện {} sau {} dòng", eventId, written, e);
            markIncomplete(csv);
            recordExport(organizerId, eventId, written, false);
            return written;
        }
        log.info("Đã xuất {} khách của sự kiện {} trong {} ms", written, eventId,
                (System.nanoTime() - started) / 1_000_000);
        recordExport(organizerId, eventId, written, true);
        return written;
    }

    /**
     * Danh sách khách có họ tên, email: ai tải, lúc nào, bao nhiêu dòng đều được ghi lịch sử (ADR-023), kể cả khi file
     * bị cắt giữa chừng. Không có transaction để gắn vào nên ghi riêng; ghi lỗi thì chỉ log, file đã gửi rồi.
     */
    private void recordExport(UUID organizerId, UUID eventId, int rows, boolean complete) {
        try {
            audit.recordNow(AuditEntry.of(AuditAction.ATTENDEES_EXPORTED, eventId).organizer(organizerId)
                    .event(eventId).detail("rows", rows).detail("complete", complete));
        } catch (RuntimeException e) {
            log.error("Không ghi được lịch sử tải danh sách khách sự kiện {}", eventId, e);
        }
    }

    private Batch batch(UUID eventId, AttendeeRow after) {
        List<AttendeeRow> rows = tickets.attendeeBatch(eventId, after, BATCH_SIZE);
        List<UUID> owners = rows.stream().map(AttendeeRow::ownerUserId).toList();
        return new Batch(rows, owners.isEmpty() ? Map.of() : contactsWithOneRetry(owners));
    }

    /** identity khởi động lại mất vài giây: thử lại một lần trước khi bỏ cuộc giữa một file dài. */
    private Map<UUID, UserContact> contactsWithOneRetry(List<UUID> owners) {
        try {
            return users.findByIds(owners);
        } catch (BusinessException e) {
            if (e.code() != ErrorCode.IDENTITY_UNAVAILABLE) {
                throw e;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw e;
            }
            return users.findByIds(owners);
        }
    }

    private static List<String> cells(AttendeeRow row, UserContact owner, ZoneId zone) {
        return java.util.Arrays.asList(row.ticketId().toString(), row.ticketType(), row.seat(),
                row.status() == TicketStatus.USED ? "Đã vào cổng" : "Chưa vào cổng",
                owner == null ? "(tài khoản không còn)" : owner.fullName(),
                owner == null ? null : owner.email(),
                row.checkedInAt() == null ? null : TIME.format(row.checkedInAt().atZone(zone)),
                row.gate());
    }

    private static void markIncomplete(CsvWriter csv) {
        try {
            csv.comment("LỖI: file chưa đầy đủ do lỗi hệ thống giữa chừng, hãy tải lại").flush();
        } catch (UncheckedIOException ignored) {
            // client đã ngắt kết nối, không còn chỗ để báo
        }
    }

    private record Batch(List<AttendeeRow> rows, Map<UUID, UserContact> contacts) {}
}
