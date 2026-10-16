package com.ticket2h.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Định kỳ đẩy outbox lên Kafka. Bật/tắt bằng {@code ticket2h.outbox.relay.enabled}. */
@Component
@ConditionalOnBooleanProperty("ticket2h.outbox.relay.enabled")
class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    static final int BATCH_SIZE = 100;

    private final OutboxBatchPublisher publisher;

    OutboxRelay(OutboxBatchPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${ticket2h.outbox.relay.interval}")
    void relay() {
        try {
            int published;
            do {
                published = publisher.publishBatch(BATCH_SIZE);
            } while (published == BATCH_SIZE);
        } catch (RuntimeException e) {
            log.warn("Relay outbox lỗi, thử lại ở lượt sau: {}", e.toString());
        }
    }
}
