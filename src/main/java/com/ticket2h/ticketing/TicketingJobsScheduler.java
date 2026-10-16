package com.ticket2h.ticketing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty("ticket2h.jobs.enabled")
class TicketingJobsScheduler {

    private final PaidOrderSweeper sweeper;

    TicketingJobsScheduler(PaidOrderSweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void issueTicketsForStuckOrders() {
        sweeper.sweep();
    }
}
