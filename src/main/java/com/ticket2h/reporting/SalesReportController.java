package com.ticket2h.reporting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.catalog.OrganizerCatalogService;
import com.ticket2h.common.security.AuthUser;
import com.ticket2h.ordering.Order;
import com.ticket2h.reporting.SalesReportService.SalesReport;

/** Báo cáo doanh thu của ban tổ chức (chỉ vai trò ORGANIZER: SecurityConfig chặn theo /organizer/**). */
@RestController
@RequestMapping("/organizer/events/{eventId}")
class SalesReportController {

    private final OrganizerCatalogService organizers;
    private final SalesReportService reports;

    SalesReportController(OrganizerCatalogService organizers, SalesReportService reports) {
        this.organizers = organizers;
        this.reports = reports;
    }

    /**
     * {@code groupBy}: DAY (mặc định), TICKET_TYPE, CHANNEL. {@code sort}: KEY, ORDERS, TICKETS, REVENUE.
     * {@code direction}: ASC, DESC. {@code from}, {@code to}: ngày YYYY-MM-DD theo múi giờ của sự kiện.
     * Giá trị khác các giá trị trên thì 400.
     */
    @GetMapping("/sales")
    SalesReportResponse sales(AuthUser caller, @PathVariable UUID eventId,
            @RequestParam(defaultValue = "DAY") SalesGroupBy groupBy,
            @RequestParam(required = false) SalesSort sort,
            @RequestParam(required = false) SortDirection direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return SalesReportResponse.of(reports.report(organizers.organizerOf(caller), eventId, groupBy, sort, direction,
                from, to));
    }

    record SalesReportResponse(UUID eventId, String timeZone, String currency, SalesGroupBy groupBy, SalesSort sort,
            SortDirection direction, LocalDate from, LocalDate to, SalesTotals totals, List<SalesRow> rows) {
        static SalesReportResponse of(SalesReport r) {
            return new SalesReportResponse(r.event().id(), r.event().timeZone().getId(), Order.DEFAULT_CURRENCY,
                    r.groupBy(), r.sort(), r.direction(), r.from(), r.to(),
                    new SalesTotals(r.totals().orders(), r.totals().tickets(), r.totals().revenue()),
                    r.rows().stream().map(row -> new SalesRow(row.key(), row.label(), row.orders(), row.tickets(),
                            row.revenue())).toList());
        }
    }

    record SalesTotals(long orders, long tickets, BigDecimal revenue) {}

    /**
     * {@code key}: ngày (YYYY-MM-DD), id loại vé, hoặc mã kênh (WEB, MOBILE_APP). {@code label}: tên loại vé, null với
     * hai cách gom còn lại. {@code orders}: null khi gom theo loại vé.
     */
    record SalesRow(String key, String label, Long orders, long tickets, BigDecimal revenue) {}
}
