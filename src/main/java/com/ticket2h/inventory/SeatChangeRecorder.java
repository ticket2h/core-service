package com.ticket2h.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Gom các ghế đổi trạng thái trong một transaction, phát đi SAU KHI commit (ADR-024): transaction rollback thì không ai
 * thấy thay đổi chưa từng xảy ra. Mỗi sự kiện một message cho cả transaction, không phải mỗi ghế một message.
 */
@Component
class SeatChangeRecorder {

    private final SeatChangeBus bus;
    private final SeatGate gate;

    SeatChangeRecorder(SeatChangeBus bus, SeatGate gate) {
        this.bus = bus;
        this.gate = gate;
    }

    void record(UUID eventId, List<SeatChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            committed(eventId, changes);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<UUID, List<SeatChange>> pending = (Map<UUID, List<SeatChange>>) TransactionSynchronizationManager
                .getResource(this);
        if (pending == null) {
            Map<UUID, List<SeatChange>> created = new LinkedHashMap<>();
            TransactionSynchronizationManager.bindResource(this, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    created.forEach(SeatChangeRecorder.this::committed);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(SeatChangeRecorder.this);
                }
            });
            pending = created;
        }
        pending.computeIfAbsent(eventId, id -> new ArrayList<>()).addAll(changes);
    }

    private void committed(UUID eventId, List<SeatChange> changes) {
        gate.committed(eventId, changes);
        bus.publish(eventId, changes);
    }
}
