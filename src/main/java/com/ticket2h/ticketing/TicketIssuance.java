package com.ticket2h.ticketing;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.events.TicketsIssued;
import com.ticket2h.common.events.Topics;
import com.ticket2h.ordering.Order;
import com.ticket2h.ordering.OrderItem;
import com.ticket2h.ordering.OrderService;
import com.ticket2h.ordering.OrderStatus;
import com.ticket2h.outbox.OutboxWriter;

/**
 * Phát vé cho đơn đã PAID. Nhận trùng message (relay gửi lại, consumer xử lý lại) không tạo thêm vé:
 * lớp chặn thứ nhất là trạng thái đơn (FULFILLED thì dừng), lớp thứ hai là
 * {@code uq_tickets_order_item_seq} + {@code ON CONFLICT DO NOTHING}.
 */
@Service
public class TicketIssuance {

    private static final Logger log = LoggerFactory.getLogger(TicketIssuance.class);

    private final OrderService orders;
    private final TicketStore tickets;
    private final OutboxWriter outbox;
    private final Clock clock;

    public TicketIssuance(OrderService orders, TicketStore tickets, OutboxWriter outbox, Clock clock) {
        this.orders = orders;
        this.tickets = tickets;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public void issueFor(UUID orderId) {
        Optional<Order> locked = orders.lockById(orderId);   // thứ tự khoá: order → tickets
        if (locked.isEmpty()) {
            log.warn("Nhận OrderPaid cho đơn không tồn tại {}", orderId);
            return;
        }
        Order order = locked.get();
        if (order.getStatus() == OrderStatus.FULFILLED) {
            return;   // đã phát rồi: message trùng
        }
        if (order.getStatus() != OrderStatus.PAID) {
            log.warn("Nhận OrderPaid nhưng đơn {} đang {}, bỏ qua", orderId, order.getStatus());
            return;
        }

        Instant now = clock.instant();
        List<TicketStore.NewTicket> newTickets = new ArrayList<>();
        for (OrderItem item : orders.items(orderId)) {
            for (int seq = 1; seq <= item.getQuantity(); seq++) {
                newTickets.add(new TicketStore.NewTicket(orderId, item.getId(), seq, order.getEventId(),
                        item.getTicketTypeId(), item.getSeatId(), order.getUserId()));
            }
        }
        tickets.insertTickets(newTickets, now);
        order.markFulfilled(now);

        List<UUID> ticketIds = tickets.ticketIdsOfOrder(orderId);
        outbox.write("Order", orderId, Topics.TICKETS_ISSUED, Topics.TICKET_EVENTS,
                new TicketsIssued(orderId, order.getUserId(), ticketIds));
        log.info("Đã phát {} vé cho đơn {}", ticketIds.size(), orderId);
    }
}
