package com.ticket2h.ticketing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.ticket2h.common.events.EventMessages;
import com.ticket2h.common.events.OrderPaid;
import com.ticket2h.common.events.Topics;

/** Consumer {@code ticket2h.order-events}: nhận OrderPaid thì phát vé. Lỗi thì error handler thử lại rồi DLT. */
@Component
class TicketIssuer {

    private final EventMessages messages;
    private final TicketIssuance issuance;

    TicketIssuer(EventMessages messages, TicketIssuance issuance) {
        this.messages = messages;
        this.issuance = issuance;
    }

    @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "ticket-issuer")
    void onOrderEvent(ConsumerRecord<String, String> record) {
        if (!Topics.ORDER_PAID.equals(messages.eventType(record))) {
            return;
        }
        OrderPaid event = messages.payload(record, OrderPaid.class);
        issuance.issueFor(EventMessages.required(event.orderId(), "orderId"));
    }
}
