package com.ticket2h.common.time;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Chuyển {@link Instant} sang kiểu mà driver PostgreSQL map chắc chắn vào {@code timestamptz}. */
public final class Times {

    public static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Times() {}
}
