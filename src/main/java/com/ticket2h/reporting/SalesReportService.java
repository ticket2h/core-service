package com.ticket2h.reporting;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.catalog.EventSnapshot;
import com.ticket2h.common.error.ValidationException;
import com.ticket2h.reporting.SalesReportStore.Row;
import com.ticket2h.reporting.SalesReportStore.Totals;

/** Báo cáo doanh thu cho ban tổ chức, đọc từ bảng tổng hợp (chậm hơn thực tế tối đa vài giây, ADR-016). */
@Service
public class SalesReportService {

    private final CatalogQueries catalog;
    private final SalesReportStore store;

    SalesReportService(CatalogQueries catalog, SalesReportStore store) {
        this.catalog = catalog;
        this.store = store;
    }

    /**
     * {@code sort}, {@code direction} null thì: theo ngày tăng dần khi gom theo ngày, còn lại theo doanh thu giảm dần.
     * {@code from}, {@code to}: ngày theo múi giờ của sự kiện, tính cả hai đầu; null là không giới hạn.
     */
    @Transactional(readOnly = true)
    public SalesReport report(UUID organizerId, UUID eventId, SalesGroupBy groupBy, SalesSort sort,
            SortDirection direction, LocalDate from, LocalDate to) {
        EventSnapshot event = catalog.ownedEvent(organizerId, eventId);
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("Ngày bắt đầu (from) phải trước hoặc bằng ngày kết thúc (to)");
        }
        if (groupBy == SalesGroupBy.TICKET_TYPE && sort == SalesSort.ORDERS) {
            throw new ValidationException(
                    "Xem theo loại vé thì không sắp xếp theo số đơn được (một đơn có thể mua nhiều loại vé)");
        }
        SalesSort effectiveSort = sort != null ? sort : groupBy == SalesGroupBy.DAY ? SalesSort.KEY : SalesSort.REVENUE;
        SortDirection effectiveDirection = direction != null ? direction
                : effectiveSort == SalesSort.KEY ? SortDirection.ASC : SortDirection.DESC;
        return new SalesReport(event, groupBy, effectiveSort, effectiveDirection, from, to,
                store.totals(eventId, from, to),
                store.rows(eventId, groupBy, effectiveSort, effectiveDirection, from, to));
    }

    public record SalesReport(EventSnapshot event, SalesGroupBy groupBy, SalesSort sort, SortDirection direction,
            LocalDate from, LocalDate to, Totals totals, List<Row> rows) {}
}
