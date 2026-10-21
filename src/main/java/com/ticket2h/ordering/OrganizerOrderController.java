package com.ticket2h.ordering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.catalog.OrganizerCatalogService;
import com.ticket2h.common.security.AuthUser;
import com.ticket2h.identity.UserDirectory.UserContact;
import com.ticket2h.ordering.OrganizerOrderList.OrderPage;

/** Đơn của một sự kiện, cho ban tổ chức (chỉ vai trò ORGANIZER: SecurityConfig chặn theo /organizer/**). */
@RestController
@RequestMapping("/organizer/events/{eventId}")
class OrganizerOrderController {

    private final OrganizerCatalogService organizers;
    private final OrganizerOrderList orders;

    OrganizerOrderController(OrganizerCatalogService organizers, OrganizerOrderList orders) {
        this.organizers = organizers;
        this.orders = orders;
    }

    /**
     * Mới tạo trước, phân trang keyset: trang sau gửi lại {@code nextCursor} của trang trước trong {@code cursor}.
     * {@code status}: lọc theo trạng thái đơn. {@code limit}: 1..100 (lớn hơn thì dùng 100). Không có tổng số đơn.
     */
    @GetMapping("/orders")
    OrganizerOrderPage orders(AuthUser caller, @PathVariable UUID eventId,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) int limit) {
        return OrganizerOrderPage.of(orders.page(organizers.organizerOf(caller), eventId, status, cursor, limit));
    }

    record OrganizerOrderPage(List<OrganizerOrderRow> items, int limit, String nextCursor) {
        static OrganizerOrderPage of(OrderPage page) {
            return new OrganizerOrderPage(page.rows().stream().map(r -> {
                UserContact buyer = page.buyers().get(r.buyerId());
                return new OrganizerOrderRow(r.orderId(), r.status(), r.salesChannel(), r.totalAmount(), r.tickets(),
                        r.createdAt(), r.paidAt(),
                        buyer == null ? null : new OrderBuyer(buyer.fullName(), buyer.email()));
            }).toList(), page.limit(), page.nextCursor());
        }
    }

    /** {@code buyer}: null khi tài khoản người mua không còn ở identity. */
    record OrganizerOrderRow(UUID orderId, OrderStatus status, SalesChannel salesChannel, BigDecimal totalAmount,
            int tickets, Instant createdAt, Instant paidAt, OrderBuyer buyer) {}

    record OrderBuyer(String fullName, String email) {}
}
