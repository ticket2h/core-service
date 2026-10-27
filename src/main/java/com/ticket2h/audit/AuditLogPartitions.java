package com.ticket2h.audit;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.ticket2h.common.persistence.MonthlyPartitions;

/** Giữ sẵn bảng con theo tháng của {@code audit_logs} cho tháng này và {@value #MONTHS_AHEAD} tháng tới (ADR-023). */
@Component
public class AuditLogPartitions {

    static final int MONTHS_AHEAD = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    AuditLogPartitions(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Trả số bảng mới tạo. */
    public int ensureAhead() {
        YearMonth now = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        return MonthlyPartitions.ensure(jdbc, "audit_logs", now, now.plusMonths(MONTHS_AHEAD));
    }
}
