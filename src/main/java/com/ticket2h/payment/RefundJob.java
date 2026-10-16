package com.ticket2h.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.ticket2h.payment.MockPayClient.MockPayException;
import com.ticket2h.payment.MockPayClient.RefundRequest;

/**
 * Hoàn tiền các payment {@code REFUND_PENDING}. Trạng thái này chính là "ý định hoàn tiền" đã lưu bền
 * vững trong transaction của webhook; job này thực hiện, ngoài transaction. {@code idempotencyKey =
 * payment.id} nên app sập sau khi gọi MockPay rồi thử lại cũng không hoàn hai lần.
 */
@Component
public class RefundJob {

    private static final Logger log = LoggerFactory.getLogger(RefundJob.class);
    static final int BATCH_SIZE = 20;

    private final RefundSteps steps;
    private final MockPayClient mockPay;

    RefundJob(RefundSteps steps, MockPayClient mockPay) {
        this.steps = steps;
        this.mockPay = mockPay;
    }

    private void recordFailure(RefundSteps.Lease lease, String error) {
        try {
            steps.recordFailure(lease.paymentId(), error);
        } catch (RuntimeException e) {
            log.error("Không ghi được lần hoàn tiền lỗi của payment {}", lease.paymentId(), e);
        }
    }

    /** @return số payment đã hoàn tiền thành công trong lượt này */
    public int run() {
        int refunded = 0;
        for (RefundSteps.Lease lease : steps.lease(BATCH_SIZE)) {
            try {
                mockPay.refund(new RefundRequest(lease.gatewayTransactionId(), lease.amount(),
                        lease.paymentId().toString()));
            } catch (MockPayException e) {
                recordFailure(lease, e.getMessage());
                continue;
            }
            try {
                steps.markRefunded(lease.paymentId());
                refunded++;
            } catch (RuntimeException e) {
                // Đã hoàn tiền nhưng chưa lưu được: hết lease thì thử lại, idempotencyKey chặn hoàn hai lần
                log.error("Đã hoàn tiền payment {} nhưng không lưu được trạng thái", lease.paymentId(), e);
            }
        }
        return refunded;
    }
}
