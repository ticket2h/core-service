package com.ticket2h.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnBooleanProperty("ticket2h.jobs.enabled")
class PaymentJobsScheduler {

    private final RefundJob refundJob;
    private final PaymentReconciliation reconciliation;

    PaymentJobsScheduler(RefundJob refundJob, PaymentReconciliation reconciliation) {
        this.refundJob = refundJob;
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    void refund() {
        refundJob.run();
    }

    /** Đối soát với cổng (ADR-022): mỗi phút xét các payment đến hạn hỏi lại. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    void reconcile() {
        reconciliation.run();
    }
}
