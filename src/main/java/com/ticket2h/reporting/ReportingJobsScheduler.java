package com.ticket2h.reporting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Chỉ chạy khi {@code ticket2h.jobs.enabled=true}. Test gọi {@link SalesRollup#applyPending()} trực tiếp. */
@Component
@ConditionalOnBooleanProperty("ticket2h.jobs.enabled")
class ReportingJobsScheduler {

    private final SalesRollup rollup;

    ReportingJobsScheduler(SalesRollup rollup) {
        this.rollup = rollup;
    }

    /** Báo cáo chậm hơn thực tế tối đa khoảng 5 giây. */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    void applySalesRollup() {
        rollup.applyPending();
    }
}
