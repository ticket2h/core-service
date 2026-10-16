package com.ticket2h.ordering;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.time.Times;

/**
 * Dọn đơn quá hạn giữ chỗ (quá thêm {@code expiry-grace}). Mỗi đơn một transaction: xử lý nhiều
 * đơn chung một transaction sẽ giữ khoá của nhiều user và loại vé lộn xộn, dễ deadlock.
 */
@Component
public class OrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);
    static final int BATCH_SIZE = 100;

    private final OrderRepository orders;
    private final OrderExpirer expirer;
    private final Ticket2hProperties properties;
    private final Clock clock;

    OrderExpiryJob(OrderRepository orders, OrderExpirer expirer, Ticket2hProperties properties, Clock clock) {
        this.orders = orders;
        this.expirer = expirer;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return số đơn đã chuyển EXPIRED */
    public int sweep() {
        Instant cutoff = clock.instant().minus(properties.order().expiryGrace());
        List<UUID> candidates = orders.findExpirableIds(Times.utc(cutoff), BATCH_SIZE);   // không khoá
        int expired = 0;
        for (UUID orderId : candidates) {
            try {
                if (expirer.expireOne(orderId, cutoff)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // Một đơn lỗi không được chặn các đơn còn lại trong lô
                log.error("Không dọn được đơn hết hạn {}, thử lại ở lượt sau", orderId, e);
            }
        }
        if (expired > 0) {
            log.info("Đã dọn {} đơn hết hạn giữ chỗ", expired);
        }
        return expired;
    }
}
