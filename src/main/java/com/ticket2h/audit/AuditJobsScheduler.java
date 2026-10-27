package com.ticket2h.audit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty("ticket2h.jobs.enabled")
class AuditJobsScheduler {

    private final AuditLogPartitions partitions;

    AuditJobsScheduler(AuditLogPartitions partitions) {
        this.partitions = partitions;
    }

    /** Lúc khởi động và mỗi ngày một lần: bảng con theo tháng của audit_logs cho 3 tháng tới. */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000, initialDelay = 15_000)
    void createAuditLogPartitions() {
        partitions.ensureAhead();
    }
}
