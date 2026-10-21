package com.ticket2h.ticketing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty("ticket2h.jobs.enabled")
class TicketingJobsScheduler {

    private final PaidOrderSweeper sweeper;
    private final CheckinLogPartitions partitions;

    TicketingJobsScheduler(PaidOrderSweeper sweeper, CheckinLogPartitions partitions) {
        this.sweeper = sweeper;
        this.partitions = partitions;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void issueTicketsForStuckOrders() {
        sweeper.sweep();
    }

    /** Lúc khởi động và mỗi ngày một lần: bảng con theo tháng của checkin_logs cho 3 tháng tới. */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000, initialDelay = 10_000)
    void createCheckinLogPartitions() {
        partitions.ensureAhead();
    }
}
