package com.ticket2h.ticketing;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.ordering.OrderService;
import com.ticket2h.outbox.OutboxWriter;

/** Bọc TicketIssuance để giả lập consumer lỗi giữa chừng (CrashRecoveryIT). */
@TestConfiguration(proxyBeanMethods = false)
public class FlakyIssuanceConfig {

    @Bean
    @Primary
    FlakyTicketIssuance flakyTicketIssuance(OrderService orders, TicketStore tickets, OutboxWriter outbox, Clock clock) {
        return new FlakyTicketIssuance(orders, tickets, outbox, clock);
    }

    public static class FlakyTicketIssuance extends TicketIssuance {

        private final AtomicInteger failuresRemaining = new AtomicInteger();
        private final AtomicInteger calls = new AtomicInteger();

        FlakyTicketIssuance(OrderService orders, TicketStore tickets, OutboxWriter outbox, Clock clock) {
            super(orders, tickets, outbox, clock);
        }

        @Override
        @Transactional
        public void issueFor(UUID orderId) {
            calls.incrementAndGet();
            if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new IllegalStateException("Giả lập consumer lỗi giữa chừng");
            }
            super.issueFor(orderId);
        }

        public void failNext(int times) {
            failuresRemaining.set(times);
        }

        public int calls() {
            return calls.get();
        }

        public void reset() {
            failuresRemaining.set(0);
            calls.set(0);
        }
    }
}
