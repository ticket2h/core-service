package com.ticket2h.ticketing;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ticket2h.ordering.OrderService;

/**
 * Lưới an toàn cho phát vé: đơn PAID quá {@link #GRACE} mà chưa có vé (message OrderPaid vào DLT sau
 * nhiều lần lỗi, hoặc bị xoá khỏi Kafka) thì phát vé trực tiếp. {@link TicketIssuance#issueFor} vốn
 * idempotent nên chạy trùng với consumer cũng không tạo thêm vé.
 */
@Component
public class PaidOrderSweeper {

    private static final Logger log = LoggerFactory.getLogger(PaidOrderSweeper.class);
    static final Duration GRACE = Duration.ofMinutes(5);
    static final int BATCH_SIZE = 50;

    private final OrderService orders;
    private final TicketIssuance issuance;
    private final Clock clock;

    PaidOrderSweeper(OrderService orders, TicketIssuance issuance, Clock clock) {
        this.orders = orders;
        this.issuance = issuance;
        this.clock = clock;
    }

    /** @return số đơn đã xử lý không lỗi */
    public int sweep() {
        List<UUID> stuck = orders.paidButNotFulfilledBefore(clock.instant().minus(GRACE), BATCH_SIZE);
        int issued = 0;
        for (UUID orderId : stuck) {
            try {
                issuance.issueFor(orderId);   // mỗi đơn một transaction
                issued++;
            } catch (RuntimeException e) {
                log.error("Không phát được vé cho đơn {} đã PAID, thử lại ở lượt sau", orderId, e);
            }
        }
        if (!stuck.isEmpty()) {
            log.warn("Đã phát bù vé cho {}/{} đơn PAID quá {} chưa có vé", issued, stuck.size(), GRACE);
        }
        return issued;
    }
}
