package com.ticket2h.ticketing;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.common.error.NotFoundException;
import com.ticket2h.common.events.TicketTransferred;
import com.ticket2h.common.events.Topics;
import com.ticket2h.outbox.OutboxWriter;

/**
 * Phần ghi dữ liệu của chuyển nhượng, trong một transaction. Đổi chủ và tăng {@code token_version} trong một câu
 * lệnh có điều kiện, nên QR cũ mất hiệu lực ngay. Đua với check-in: hai câu lệnh nhắm cùng một dòng nên chạy lần
 * lượt, bên đến sau thấy điều kiện của mình không còn đúng (vé USED, hoặc version đã đổi). Bean riêng (không phải
 * method trong {@link TicketTransferService}) vì gọi method {@code @Transactional} trong cùng class thì không có
 * transaction.
 */
@Component
class TicketTransferSteps {

    private final TicketStore tickets;
    private final OutboxWriter outbox;
    private final Ticket2hProperties properties;
    private final Clock clock;

    TicketTransferSteps(TicketStore tickets, OutboxWriter outbox, Ticket2hProperties properties, Clock clock) {
        this.tickets = tickets;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    TicketView transfer(TicketView ticket, UUID fromUserId, UUID toUserId) {
        Instant now = clock.instant();
        UUID ticketId = ticket.ticketId();
        if (!now.isBefore(ticket.eventStartsAt().minus(properties.ticket().transferCutoff()))) {
            throw notTransferable("Không chuyển nhượng được trong vòng "
                    + properties.ticket().transferCutoff().toHours() + " giờ trước giờ diễn");
        }

        Optional<Integer> newVersion = tickets.transfer(ticketId, fromUserId, toUserId);
        if (newVersion.isEmpty()) {
            // 0 dòng: vé vừa đổi chủ (404) hoặc vừa được dùng/huỷ (409). Đọc lại để phân biệt.
            TicketView current = tickets.view(ticketId).orElseThrow(() -> new NotFoundException("vé"));
            if (!current.ownerUserId().equals(fromUserId)) {
                throw new NotFoundException("vé");
            }
            throw notTransferable("Vé đã được sử dụng hoặc đã huỷ");
        }
        tickets.insertTransfer(ticketId, fromUserId, toUserId, newVersion.get(), now);
        outbox.write("Ticket", ticketId, Topics.TICKET_TRANSFERRED, Topics.TICKET_EVENTS,
                new TicketTransferred(ticketId, ticket.eventId(), fromUserId, toUserId, newVersion.get(), now));
        return tickets.view(ticketId).orElseThrow();
    }

    private static BusinessException notTransferable(String detail) {
        return new BusinessException(ErrorCode.TICKET_NOT_TRANSFERABLE, detail);
    }
}
