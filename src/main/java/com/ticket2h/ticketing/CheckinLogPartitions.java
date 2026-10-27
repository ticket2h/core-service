package com.ticket2h.ticketing;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.ticket2h.common.persistence.MonthlyPartitions;

/**
 * Tạo trước bảng con theo tháng của {@code checkin_logs} (ADR-019). Mỗi tháng (giờ UTC) một bảng
 * {@code checkin_logs_YYYY_MM}. Job chạy mỗi ngày, luôn giữ sẵn bảng cho {@value #MONTHS_AHEAD} tháng tới, nên dòng
 * mới không rơi vào bảng {@code checkin_logs_default}. Nếu job ngừng quá {@value #MONTHS_AHEAD} tháng, check-in vẫn
 * chạy (dòng vào bảng default), nhưng tạo bảng cho tháng đó sẽ báo lỗi cho tới khi chuyển các dòng đó đi (ADR-019).
 */
@Component
public class CheckinLogPartitions {

    static final int MONTHS_AHEAD = 3;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    CheckinLogPartitions(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Bảng con cho tháng này và {@value #MONTHS_AHEAD} tháng sau. Trả số bảng mới tạo. */
    public int ensureAhead() {
        YearMonth now = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        return ensure(now, now.plusMonths(MONTHS_AHEAD));
    }

    /** Bảng con cho mọi tháng từ {@code first} tới {@code last} (tính cả hai đầu). Đã có thì bỏ qua. */
    public int ensure(YearMonth first, YearMonth last) {
        return MonthlyPartitions.ensure(jdbc, "checkin_logs", first, last);
    }
}
