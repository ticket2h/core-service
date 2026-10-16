package com.ticket2h.outbox;

import java.time.Clock;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.time.Times;

/** Xoá outbox đã gửi lâu hơn {@code ticket2h.outbox.retention} (mặc định 7 ngày). */
@Component
@ConditionalOnBooleanProperty("ticket2h.jobs.enabled")
class OutboxCleanup {

    private final NamedParameterJdbcTemplate jdbc;
    private final Ticket2hProperties properties;
    private final Clock clock;

    OutboxCleanup(NamedParameterJdbcTemplate jdbc, Ticket2hProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    void deletePublished() {
        jdbc.update("DELETE FROM outbox_events WHERE published_at < :cutoff",
                Map.of("cutoff", Times.utc(clock.instant().minus(properties.outbox().retention()))));
    }
}
