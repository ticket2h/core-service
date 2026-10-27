package com.ticket2h.common.persistence;

import java.time.YearMonth;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Tạo bảng con theo tháng (giờ UTC) cho bảng partition theo khoảng thời gian: {@code <bảng>_YYYY_MM}. Dùng cho
 * {@code checkin_logs} (ADR-019) và {@code audit_logs} (ADR-023). Bảng cha có bảng {@code DEFAULT} hứng dòng của
 * tháng chưa có bảng con, nên ghi không bao giờ lỗi vì thiếu bảng con; nhưng nếu bảng default đã có dòng của một tháng
 * thì tạo bảng con cho tháng đó sẽ lỗi (ghi log, đi tiếp các tháng khác).
 */
public final class MonthlyPartitions {

    private static final Logger log = LoggerFactory.getLogger(MonthlyPartitions.class);

    /** Bảng con cho mọi tháng từ {@code first} tới {@code last} (cả hai đầu), đã có thì bỏ qua. Trả số bảng mới. */
    public static int ensure(NamedParameterJdbcTemplate jdbc, String parent, YearMonth first, YearMonth last) {
        int created = 0;
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            String table = tableName(parent, month);
            if (exists(jdbc, table)) {
                continue;
            }
            try {
                // Tên bảng và mốc thời gian dựng từ hằng số trong code và YearMonth, không có dữ liệu người dùng
                jdbc.getJdbcTemplate().execute("CREATE TABLE " + table + " PARTITION OF " + parent
                        + " FOR VALUES FROM ('" + month.atDay(1) + "T00:00:00Z') TO ('"
                        + month.plusMonths(1).atDay(1) + "T00:00:00Z')");
                created++;
                log.info("Đã tạo bảng con {} cho {}", table, parent);
            } catch (DataAccessException e) {
                // Thường gặp nhất: bảng default đã có dòng của tháng này (job ngừng chạy quá lâu). Xem ADR-019.
                log.error("Không tạo được bảng con {} của {}: {}", table, parent,
                        e.getMostSpecificCause().getMessage());
            }
        }
        return created;
    }

    public static String tableName(String parent, YearMonth month) {
        return "%s_%04d_%02d".formatted(parent, month.getYear(), month.getMonthValue());
    }

    private static boolean exists(NamedParameterJdbcTemplate jdbc, String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(:name) IS NOT NULL",
                Map.of("name", table), Boolean.class));
    }

    private MonthlyPartitions() {}
}
