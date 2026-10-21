package com.ticket2h.ticketing;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.ticketing.CheckinLogStore.CheckinLogRow;

/**
 * Bọc {@link CheckinLogStore#stream} trong một transaction chỉ đọc (để driver đọc theo cursor). Tách bean riêng để
 * {@code @Transactional} có hiệu lực khi {@link CheckinLogExport} gọi vào.
 */
@Component
class CheckinLogReader {

    private final CheckinLogStore logs;

    CheckinLogReader(CheckinLogStore logs) {
        this.logs = logs;
    }

    @Transactional(readOnly = true)
    void read(UUID eventId, Instant from, Instant to, Consumer<CheckinLogRow> row) {
        logs.stream(eventId, from, to, row);
    }
}
