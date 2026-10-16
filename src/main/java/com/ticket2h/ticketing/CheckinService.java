package com.ticket2h.ticketing;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.catalog.CatalogQueries;

/**
 * Soát vé. Một câu UPDATE có điều kiện quyết định ai thắng: hai cổng quét cùng một QR thì chỉ một
 * câu cập nhật được 1 dòng, câu kia 0 dòng và trả ALREADY_USED. Mọi lượt quét đều ghi checkin_logs
 * trong cùng transaction, kể cả thất bại; vì vậy method trả kết quả thay vì ném exception.
 */
@Service
public class CheckinService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final QrTokenCodec codec;
    private final TicketStore tickets;
    private final CatalogQueries catalog;
    private final Clock clock;

    CheckinService(QrTokenCodec codec, TicketStore tickets, CatalogQueries catalog, Clock clock) {
        this.codec = codec;
        this.tickets = tickets;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Transactional
    public CheckinOutcome checkin(UUID staffId, String qrToken, String gate) {
        Instant now = clock.instant();
        TicketClaims claims;
        try {
            claims = codec.decode(qrToken);
        } catch (InvalidTicketTokenException e) {
            tickets.insertCheckinLog(null, null, staffId, gate, CheckinResult.INVALID_TOKEN, now);
            return new CheckinOutcome(CheckinResult.INVALID_TOKEN, "Mã QR không hợp lệ", null);
        }

        if (!catalog.isAssignedStaff(claims.eventId(), staffId)) {
            tickets.insertCheckinLog(claims.ticketId(), claims.eventId(), staffId, gate,
                    CheckinResult.WRONG_EVENT_STAFF, now);
            return new CheckinOutcome(CheckinResult.WRONG_EVENT_STAFF,
                    "Bạn không được phân công soát vé sự kiện này", null);
        }

        int updated = tickets.markUsed(claims.ticketId(), claims.eventId(), claims.tokenVersion(), staffId, gate, now);
        TicketView ticket = tickets.view(claims.ticketId())
                .filter(t -> t.eventId().equals(claims.eventId()))
                .orElse(null);
        CheckinResult result = updated == 1 ? CheckinResult.OK : explain(ticket, claims);
        tickets.insertCheckinLog(claims.ticketId(), claims.eventId(), staffId, gate, result, now);
        return new CheckinOutcome(result, message(result, ticket), result == CheckinResult.INVALID_TOKEN ? null : ticket);
    }

    /** UPDATE ảnh hưởng 0 dòng: đọc vé để giải thích vì sao. */
    private static CheckinResult explain(TicketView ticket, TicketClaims claims) {
        if (ticket == null) {
            return CheckinResult.INVALID_TOKEN;
        }
        if (ticket.status() == TicketStatus.USED) {
            return CheckinResult.ALREADY_USED;
        }
        if (ticket.tokenVersion() != claims.tokenVersion()) {
            return CheckinResult.TOKEN_REVOKED;
        }
        if (ticket.status() == TicketStatus.VOID) {
            return CheckinResult.VOIDED;
        }
        return CheckinResult.INVALID_TOKEN;
    }

    /** Giờ hiển thị cho nhân viên theo múi giờ của sự kiện; database lưu UTC. */
    private static String message(CheckinResult result, TicketView ticket) {
        return switch (result) {
            case OK -> "Hợp lệ, mời vào";
            case ALREADY_USED -> "Vé đã được sử dụng lúc "
                    + TIME.format(ticket.checkedInAt().atZone(ticket.eventTimeZone()))
                    + " tại cổng " + ticket.checkedInGate();
            case TOKEN_REVOKED -> "Mã QR đã bị thu hồi (vé đã được chuyển nhượng)";
            case VOIDED -> "Vé đã bị huỷ";
            case INVALID_TOKEN -> "Mã QR không hợp lệ";
            case WRONG_EVENT_STAFF -> "Bạn không được phân công soát vé sự kiện này";
        };
    }

    public record CheckinOutcome(CheckinResult result, String message, TicketView ticket) {}
}
