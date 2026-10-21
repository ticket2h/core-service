package com.ticket2h.ordering;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.common.paging.KeysetCursor;
import com.ticket2h.identity.UserDirectory;
import com.ticket2h.identity.UserDirectory.UserContact;
import com.ticket2h.ordering.OrderListStore.OrderListRow;

/**
 * Danh sách đơn của một sự kiện cho ban tổ chức, kèm tên và email người mua (hỏi identity một lần cho cả trang). KHÔNG
 * có {@code @Transactional}: đọc trang (một câu SELECT tự commit) rồi mới gọi identity, ngoài transaction.
 */
@Service
public class OrganizerOrderList {

    static final int MAX_LIMIT = 100;

    private final CatalogQueries catalog;
    private final OrderListStore store;
    private final UserDirectory users;

    OrganizerOrderList(CatalogQueries catalog, OrderListStore store, UserDirectory users) {
        this.catalog = catalog;
        this.store = store;
        this.users = users;
    }

    /** {@code limit} quá {@value #MAX_LIMIT} thì dùng {@value #MAX_LIMIT}. {@code nextCursor} null: hết danh sách. */
    public OrderPage page(UUID organizerId, UUID eventId, OrderStatus status, String cursor, int limit) {
        catalog.ownedEvent(organizerId, eventId);
        int safeLimit = Math.min(limit, MAX_LIMIT);
        // Lấy thừa một dòng để biết còn trang sau hay không, khỏi phải đếm tổng (đếm 200.000 đơn mỗi trang rất tốn)
        List<OrderListRow> rows = store.page(eventId, status, KeysetCursor.decodeOrNull(cursor), safeLimit + 1);
        boolean more = rows.size() > safeLimit;
        List<OrderListRow> page = more ? rows.subList(0, safeLimit) : rows;
        Map<UUID, UserContact> buyers = page.isEmpty() ? Map.of()
                : users.findByIds(page.stream().map(OrderListRow::buyerId).toList());
        String next = more ? new KeysetCursor(page.getLast().createdAt(), page.getLast().orderId()).encode() : null;
        return new OrderPage(List.copyOf(page), buyers, safeLimit, next);
    }

    public record OrderPage(List<OrderListRow> rows, Map<UUID, UserContact> buyers, int limit, String nextCursor) {}
}
