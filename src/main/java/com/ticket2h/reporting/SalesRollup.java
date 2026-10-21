package com.ticket2h.reporting;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bảng tổng hợp doanh thu (ADR-016). Lúc đơn chuyển PAID, payment ghi đơn vào hàng đợi trong cùng transaction
 * ({@link #enqueue}); job {@link #applyPending} vài giây một lần cộng các đơn trong hàng đợi vào bảng tổng hợp.
 * Không cộng thẳng lúc trả tiền: mọi đơn của một sự kiện cùng ngày cùng kênh rơi vào vài dòng tổng hợp, cộng thẳng thì
 * lúc mở bán mọi webhook phải xếp hàng chờ khoá vài dòng đó (điểm nghẽn giống bộ đếm GA ở ADR-003).
 */
@Service
public class SalesRollup {

    static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(SalesRollup.class);

    private final SalesRollupStore store;
    private final SalesRollupBatch batch;

    SalesRollup(SalesRollupStore store, SalesRollupBatch batch) {
        this.store = store;
        this.batch = batch;
    }

    /** Gọi trong transaction đổi đơn sang PAID, để đơn vào hàng đợi khi và chỉ khi việc trả tiền được commit. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID orderId, Instant now) {
        store.enqueue(orderId, now);
    }

    /**
     * Cộng hết hàng đợi, mỗi lô {@value #BATCH_SIZE} đơn một transaction. Lô lỗi thì dừng, các đơn của lô đó vẫn nằm
     * trong hàng đợi (transaction rollback) và được cộng ở lượt sau. Trả số đơn đã lấy khỏi hàng đợi.
     */
    public int applyPending() {
        int total = 0;
        while (true) {
            int taken;
            try {
                taken = batch.apply(BATCH_SIZE);
            } catch (RuntimeException e) {
                log.error("Không cộng được lô đơn vào bảng tổng hợp doanh thu, thử lại ở lượt sau", e);
                return total;
            }
            total += taken;
            if (taken < BATCH_SIZE) {
                return total;
            }
        }
    }

    /**
     * Tính lại bảng tổng hợp của một sự kiện từ bảng orders, ví dụ sau khi nạp dữ liệu thẳng vào database. Giữ cùng
     * khoá với job, nên job không cộng chồng lên trong lúc tính lại.
     */
    @Transactional
    public void rebuild(UUID eventId) {
        store.lock();
        store.rebuildEvent(eventId);
    }

    /** Như {@link #rebuild} cho mọi sự kiện (công cụ sinh dữ liệu dùng sau khi nạp hàng triệu đơn). */
    @Transactional
    public void rebuildAll() {
        store.lock();
        long started = System.nanoTime();
        store.rebuildAll();
        log.info("Đã tính lại bảng tổng hợp doanh thu trong {} ms", (System.nanoTime() - started) / 1_000_000);
    }
}
