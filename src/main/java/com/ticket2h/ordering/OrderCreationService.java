package com.ticket2h.ordering;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.catalog.EventSnapshot;
import com.ticket2h.catalog.EventStatus;
import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.error.NotFoundException;
import com.ticket2h.inventory.GaInventory;
import com.ticket2h.inventory.PurchaseLimitExceededException;
import com.ticket2h.inventory.PurchaseQuotas;
import com.ticket2h.inventory.SeatHoldService;

/**
 * Tạo đơn trong MỘT transaction: đơn → hạn mức → vé GA → ghế → order_items. Bước nào thất bại
 * thì rollback toàn bộ. Gọi qua {@link OrderPlacement}, không gọi thẳng từ controller.
 */
@Service
public class OrderCreationService {

    private final Clock clock;
    private final Ticket2hProperties properties;
    private final CatalogQueries catalog;
    private final OrderPricing pricing;
    private final OrderRepository orders;
    private final OrderItemRepository orderItems;
    private final PurchaseQuotas purchaseQuotas;
    private final GaInventory gaInventory;
    private final SeatHoldService seatHolds;

    OrderCreationService(Clock clock, Ticket2hProperties properties, CatalogQueries catalog,
            OrderPricing pricing, OrderRepository orders, OrderItemRepository orderItems,
            PurchaseQuotas purchaseQuotas, GaInventory gaInventory, SeatHoldService seatHolds) {
        this.clock = clock;
        this.properties = properties;
        this.catalog = catalog;
        this.pricing = pricing;
        this.orders = orders;
        this.orderItems = orderItems;
        this.purchaseQuotas = purchaseQuotas;
        this.gaInventory = gaInventory;
        this.seatHolds = seatHolds;
    }

    @Transactional
    public CreatedOrder create(UUID userId, String idempotencyKey, String requestHash, CreateOrderRequest request) {
        Instant now = clock.instant();
        EventSnapshot event = catalog.findEvent(request.eventId())
                .filter(e -> e.status() == EventStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("sự kiện"));
        event.assertOnSale(now);

        // Kiểm tra và định giá: chỉ đọc, chưa ghi gì
        PricedOrder priced = pricing.price(event, request);
        if (priced.totalQuantity() > event.maxTicketsPerUser()) {
            throw new PurchaseLimitExceededException(event.maxTicketsPerUser());
        }

        // INSERT đơn là thao tác GHI ĐẦU TIÊN, và phải flush ngay. Request song song cùng key sẽ
        // chờ ở đây cho tới khi request đầu commit (→ unique violation → replay) hoặc rollback.
        Order order = Order.pending(userId, event.id(), idempotencyKey, requestHash, priced.total(), now,
                now.plus(properties.order().holdDuration()));
        orders.saveAndFlush(order);

        // Thứ tự khoá (B3): purchase_quotas → ticket_types theo id → seats theo id
        purchaseQuotas.reserve(userId, event.id(), priced.totalQuantity(), event.maxTicketsPerUser());
        for (PricedOrder.GaLine line : priced.gaLinesSortedByTicketType()) {
            gaInventory.take(event.id(), line.ticketTypeId(), line.quantity());
        }
        seatHolds.hold(order.getId(), event.id(), priced.allSeatIds(), now, order.getExpiresAt());

        List<OrderItem> items = orderItems.saveAll(priced.toItems(order.getId()));
        return new CreatedOrder(order, items);
    }

    public record CreatedOrder(Order order, List<OrderItem> items) {}
}
