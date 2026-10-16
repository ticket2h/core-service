package com.ticket2h.ticketing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.error.ForbiddenException;
import com.ticket2h.common.security.AuthUser;

@RestController
class TicketController {

    private final TicketQueries queries;
    private final TicketTransferService transfers;
    private final CheckinService checkins;
    private final Ticket2hProperties properties;

    TicketController(TicketQueries queries, TicketTransferService transfers, CheckinService checkins,
            Ticket2hProperties properties) {
        this.queries = queries;
        this.transfers = transfers;
        this.checkins = checkins;
        this.properties = properties;
    }

    @GetMapping("/me/tickets")
    List<TicketResponse> mine(AuthUser caller) {
        return queries.ownedBy(caller.id()).stream().map(TicketResponse::of).toList();
    }

    @GetMapping("/tickets/{ticketId}")
    TicketResponse get(AuthUser caller, @PathVariable UUID ticketId) {
        return TicketResponse.of(queries.ownedTicket(caller.id(), ticketId));
    }

    /** Ảnh PNG. {@code no-store}: QR là "chìa khoá" vào cửa, không để proxy hay trình duyệt lưu lại. */
    @GetMapping("/tickets/{ticketId}/qr")
    ResponseEntity<byte[]> qr(AuthUser caller, @PathVariable UUID ticketId) {
        byte[] png = queries.qrPng(queries.ownedTicket(caller.id(), ticketId));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    @PostMapping("/tickets/{ticketId}/transfer")
    TicketResponse transfer(AuthUser caller, @PathVariable UUID ticketId,
            @Valid @RequestBody TransferRequest request) {
        return TicketResponse.of(transfers.transfer(caller.id(), ticketId, request.recipientEmail()));
    }

    /**
     * Kết quả nghiệp vụ (vé đã dùng, QR bị thu hồi...) trả 200 kèm {@code result}: nhân viên cần biết vì
     * sao không cho vào, đó không phải lỗi hệ thống. Riêng nhân viên không được phân công thì 403.
     */
    @PostMapping("/checkin")
    CheckinResponse checkin(AuthUser caller, @Valid @RequestBody CheckinRequest request) {
        CheckinService.CheckinOutcome outcome = checkins.checkin(caller.id(), request.qrToken(), request.gate());
        if (outcome.result() == CheckinResult.WRONG_EVENT_STAFF) {
            throw new ForbiddenException(outcome.message());   // log đã được ghi và commit trong checkin()
        }
        return CheckinResponse.of(outcome);
    }

    /** Public key để máy soát vé kiểm tra QR khi mất mạng. Public key không phải bí mật. */
    @GetMapping("/ticket-keys")
    TicketKeysResponse keys() {
        return new TicketKeysResponse(properties.qr().activeKeyId(), "Ed25519", properties.qr().publicKeys());
    }

    record TransferRequest(@NotBlank @Email @Size(max = 320) String recipientEmail) {}

    record CheckinRequest(@NotBlank @Size(max = 512) String qrToken, @NotBlank @Size(max = 50) String gate) {}

    record TicketResponse(UUID ticketId, UUID orderId, UUID eventId, String eventTitle, Instant eventStartsAt,
            String ticketType, String seat, TicketStatus status, Instant issuedAt, Instant checkedInAt,
            String checkedInGate) {
        static TicketResponse of(TicketView t) {
            return new TicketResponse(t.ticketId(), t.orderId(), t.eventId(), t.eventTitle(), t.eventStartsAt(),
                    t.ticketTypeName(), t.seatLabel(), t.status(), t.issuedAt(), t.checkedInAt(), t.checkedInGate());
        }
    }

    record CheckinResponse(CheckinResult result, String message, TicketInfo ticket, Instant checkedInAt,
            String checkedInGate) {
        static CheckinResponse of(CheckinService.CheckinOutcome outcome) {
            if (outcome.ticket() == null) {
                return new CheckinResponse(outcome.result(), outcome.message(), null, null, null);
            }
            TicketView t = outcome.ticket();
            return new CheckinResponse(outcome.result(), outcome.message(),
                    new TicketInfo(t.ticketTypeName(), t.seatLabel()), t.checkedInAt(), t.checkedInGate());
        }
    }

    record TicketInfo(String ticketType, String seat) {}

    record TicketKeysResponse(String activeKeyId, String algorithm, Map<String, String> keys) {}
}
