package com.ticket2h.audit;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ghi lịch sử thao tác (ADR-023 trong infra/docs/adr). Cửa công khai của module audit cho các module khác.
 * <ul>
 *   <li>{@link #record}: {@code MANDATORY}, trong CÙNG transaction với thay đổi được ghi lại, như {@code OutboxWriter}:
 *       thay đổi rollback thì dòng lịch sử cũng mất, không có lịch sử của việc không xảy ra.</li>
 *   <li>{@link #recordNow}: transaction riêng, cho việc không có transaction để gắn vào (tải file CSV).</li>
 * </ul>
 */
@Component
public class AuditLog {

    private final AuditLogStore store;
    private final Clock clock;

    AuditLog(AuditLogStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** Người làm là người đang gọi API ({@link AuditActor#current()}). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        store.insert(AuditActor.current(), entry, clock.instant());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditActor actor, AuditEntry entry) {
        store.insert(actor, entry, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNow(AuditEntry entry) {
        store.insert(AuditActor.current(), entry, clock.instant());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordNow(AuditActor actor, AuditEntry entry) {
        store.insert(actor, entry, clock.instant());
    }
}
