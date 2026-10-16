package com.ticket2h.ticketing;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.common.error.NotFoundException;

/** Đọc vé và sinh QR. Dùng bởi controller công khai và API nội bộ cho notification-service. */
@Service
public class TicketQueries {

    private final TicketStore tickets;
    private final QrTokenCodec codec;
    private final QrImageRenderer renderer;

    TicketQueries(TicketStore tickets, QrTokenCodec codec, QrImageRenderer renderer) {
        this.tickets = tickets;
        this.codec = codec;
        this.renderer = renderer;
    }

    @Transactional(readOnly = true)
    public List<TicketView> ownedBy(UUID userId) {
        return tickets.viewsOwnedBy(userId);
    }

    /** Vé của người khác coi như không tồn tại (404). */
    @Transactional(readOnly = true)
    public TicketView ownedTicket(UUID userId, UUID ticketId) {
        return tickets.view(ticketId)
                .filter(t -> t.ownerUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("vé"));
    }

    @Transactional(readOnly = true)
    public List<TicketView> views(List<UUID> ticketIds) {
        return tickets.views(ticketIds);
    }

    @Transactional(readOnly = true)
    public Optional<TicketView> view(UUID ticketId) {
        return tickets.view(ticketId);
    }

    /** Ảnh QR của vé còn hiệu lực. Token tính lại từ dữ liệu vé, không lưu trong database. */
    public byte[] qrPng(TicketView ticket) {
        if (ticket.status() != TicketStatus.VALID) {
            throw new BusinessException(ErrorCode.TICKET_NOT_VALID, "Vé đã sử dụng hoặc đã huỷ, không còn mã QR");
        }
        return renderer.png(codec.encode(ticket.claims()));
    }

    /** Chuỗi token của QR: cho API nội bộ (notification-service soạn email) và endpoint dev thử check-in bằng curl. */
    public String qrToken(TicketView ticket) {
        return codec.encode(ticket.claims());
    }
}
