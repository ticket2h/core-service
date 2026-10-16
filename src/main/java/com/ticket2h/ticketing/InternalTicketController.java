package com.ticket2h.ticketing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.common.error.ValidationException;

/**
 * API nội bộ cho notification-service: thông tin vé để soạn email, kèm token QR của vé còn hiệu lực.
 * Token QR là "chìa khoá vào cửa" nên chỉ đi qua HTTP nội bộ lúc cần gửi email, KHÔNG nằm trong message
 * Kafka hay bảng outbox (ADR-011). Chỉ service có khoá {@code X-Internal-Api-Key} gọi được (SecurityConfig).
 */
@RestController
class InternalTicketController {

    /** Một đơn tối đa vài vé (hạn mức mỗi người); giới hạn để một lời gọi không kéo cả bảng. */
    static final int MAX_IDS = 50;

    private final TicketQueries queries;

    InternalTicketController(TicketQueries queries) {
        this.queries = queries;
    }

    /** Vé không tồn tại thì không có trong kết quả. Thứ tự giống {@code TicketQueries.views}. */
    @GetMapping("/internal/tickets")
    ResponseEntity<List<InternalTicketResponse>> tickets(@RequestParam List<UUID> ids) {
        if (ids.isEmpty() || ids.size() > MAX_IDS) {
            throw new ValidationException("ids phải có từ 1 đến " + MAX_IDS + " phần tử");
        }
        List<InternalTicketResponse> body = queries.views(ids).stream().map(this::response).toList();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private InternalTicketResponse response(TicketView t) {
        String qrToken = t.status() == TicketStatus.VALID ? queries.qrToken(t) : null;
        return new InternalTicketResponse(t.ticketId(), t.orderId(), t.eventId(), t.ownerUserId(), t.status(),
                t.tokenVersion(), t.eventTitle(), t.eventStartsAt(), t.eventTimeZone().getId(), t.ticketTypeName(),
                t.seatLabel(), qrToken);
    }

    /** {@code qrToken} chỉ có khi vé VALID. {@code eventTimeZone} là tên múi giờ IANA, ví dụ Asia/Ho_Chi_Minh. */
    record InternalTicketResponse(UUID ticketId, UUID orderId, UUID eventId, UUID ownerUserId, TicketStatus status,
            int tokenVersion, String eventTitle, Instant eventStartsAt, String eventTimeZone, String ticketTypeName,
            String seatLabel, String qrToken) {}
}
