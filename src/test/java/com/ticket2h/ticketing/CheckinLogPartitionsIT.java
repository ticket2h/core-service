package com.ticket2h.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticket2h.support.IntegrationTest;

/** checkin_logs chia bảng con theo tháng (ADR-019). */
class CheckinLogPartitionsIT extends IntegrationTest {

    @Autowired CheckinLogPartitions partitions;

    @Test
    void job_keeps_three_months_ahead_and_rows_land_in_their_month() {
        clock.set(Instant.parse("2027-05-10T08:00:00Z"));

        partitions.ensureAhead();

        for (String month : new String[] {"2027_05", "2027_06", "2027_07", "2027_08"}) {
            assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "checkin_logs_" + month))
                    .as(month).isTrue();
        }
        assertThat(partitions.ensureAhead()).as("chạy lại không tạo thêm").isZero();
        UUID event = UUID.randomUUID();
        bulkData.checkinLogs(event, UUID.randomUUID(), 1, Instant.parse("2027-07-31T23:59:59Z"), Duration.ZERO);
        bulkData.checkinLogs(event, UUID.randomUUID(), 1, Instant.parse("2027-08-01T00:00:00Z"), Duration.ZERO);
        assertThat(jdbc.queryForList("""
                SELECT tableoid::regclass::text FROM checkin_logs WHERE event_id = ? ORDER BY scanned_at
                """, String.class, event)).containsExactly("checkin_logs_2027_07", "checkin_logs_2027_08");
    }

    /** Truy vấn theo khoảng thời gian chỉ đọc bảng con của tháng đó (partition pruning). */
    @Test
    void a_query_on_one_month_only_reads_that_months_table() {
        partitions.ensure(YearMonth.of(2027, 1), YearMonth.of(2027, 3));

        String plan = String.join("\n", jdbc.queryForList("""
                EXPLAIN SELECT * FROM checkin_logs
                WHERE event_id = gen_random_uuid()
                  AND scanned_at >= '2027-02-03T00:00:00Z' AND scanned_at < '2027-02-05T00:00:00Z'
                """, String.class));

        assertThat(plan).contains("checkin_logs_2027_02").doesNotContain("checkin_logs_2027_01")
                .doesNotContain("checkin_logs_2027_03").doesNotContain("checkin_logs_default");
    }

    /** Job ngừng quá lâu, dòng đã rơi vào bảng default: không tạo được bảng của tháng đó, nhưng không làm hỏng gì. */
    @Test
    void a_month_already_holding_rows_in_the_default_table_is_reported_not_thrown() {
        UUID event = UUID.randomUUID();
        bulkData.checkinLogs(event, UUID.randomUUID(), 1, Instant.parse("2031-03-15T00:00:00Z"), Duration.ZERO);
        assertThat(jdbc.queryForObject("SELECT tableoid::regclass::text FROM checkin_logs WHERE event_id = ?",
                String.class, event)).isEqualTo("checkin_logs_default");

        int created = partitions.ensure(YearMonth.of(2031, 3), YearMonth.of(2031, 4));

        assertThat(created).isEqualTo(1);   // tháng 4 tạo được, tháng 3 thì không
        assertThat(jdbc.queryForObject("SELECT to_regclass('checkin_logs_2031_03') IS NULL", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM checkin_logs WHERE event_id = ?", Integer.class, event))
                .isEqualTo(1);
    }
}
