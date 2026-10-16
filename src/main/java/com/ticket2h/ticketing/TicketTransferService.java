package com.ticket2h.ticketing;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.common.error.NotFoundException;
import com.ticket2h.common.error.ValidationException;
import com.ticket2h.common.security.Role;
import com.ticket2h.identity.UserDirectory;
import com.ticket2h.identity.UserDirectory.UserContact;

/**
 * Chuyển vé cho người khác. KHÔNG có {@code @Transactional}: tra người nhận theo email là lời gọi HTTP sang
 * identity-service, phải nằm ngoài transaction. Phần đổi dữ liệu nằm ở {@link TicketTransferSteps} (một transaction).
 * <p>
 * Thứ tự kiểm tra giữ như ADR-009: quyền sở hữu vé TRƯỚC, rồi mới tra email, để người không có vé không dò được
 * email nào đã đăng ký. Vé có thể đổi chủ giữa lúc kiểm tra ở đây và lúc ghi: câu UPDATE có điều kiện trong
 * {@code TicketTransferSteps} kiểm tra lại, nên không có hai người cùng chuyển một vé.
 */
@Service
public class TicketTransferService {

    private final TicketStore tickets;
    private final UserDirectory users;
    private final TicketTransferSteps steps;

    TicketTransferService(TicketStore tickets, UserDirectory users, TicketTransferSteps steps) {
        this.tickets = tickets;
        this.users = users;
        this.steps = steps;
    }

    public TicketView transfer(UUID fromUserId, UUID ticketId, String recipientEmail) {
        TicketView ticket = tickets.view(ticketId)
                .filter(t -> t.ownerUserId().equals(fromUserId))
                .orElseThrow(() -> new NotFoundException("vé"));
        UserContact recipient = users.findByEmail(recipientEmail)
                .filter(u -> u.role() == Role.CUSTOMER)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECIPIENT_NOT_FOUND,
                        "Không có tài khoản khách hàng với email này"));
        if (recipient.id().equals(fromUserId)) {
            throw new ValidationException("Không thể chuyển vé cho chính mình");
        }
        return steps.transfer(ticket, fromUserId, recipient.id());
    }
}
