package com.ticket2h.ordering;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.inventory.GaInventory;
import com.ticket2h.inventory.PurchaseQuotas;
import com.ticket2h.inventory.SeatHoldService;

/** Trả lại hạn mức, vé GA, ghế của một đơn đang chờ. Dùng cho cả hết hạn lẫn huỷ. */
@Component
public class OrderRelease {

    private final OrderItemRepository orderItems;
    private final PurchaseQuotas purchaseQuotas;
    private final GaInventory gaInventory;
    private final SeatHoldService seatHolds;

    OrderRelease(OrderItemRepository orderItems, PurchaseQuotas purchaseQuotas, GaInventory gaInventory,
            SeatHoldService seatHolds) {
        this.orderItems = orderItems;
        this.purchaseQuotas = purchaseQuotas;
        this.gaInventory = gaInventory;
        this.seatHolds = seatHolds;
    }

    /** Bên gọi PHẢI đã khoá đơn ({@code FOR UPDATE}), nên kiểm tra trạng thái trong Java là an toàn. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(Order order, OrderStatus newStatus, Instant now) {
        switch (newStatus) {
            case EXPIRED -> order.markExpired(now);
            case CANCELLED -> order.cancel(now);
            default -> throw new IllegalArgumentException("release chỉ dùng cho EXPIRED hoặc CANCELLED");
        }
        List<OrderItem> items = orderItems.findByOrderId(order.getId());

        // Thứ tự khoá (B3): purchase_quotas → ticket_types theo id → seats
        int totalQuantity = items.stream().mapToInt(OrderItem::getQuantity).sum();
        if (totalQuantity > 0) {
            purchaseQuotas.release(order.getUserId(), order.getEventId(), totalQuantity);
        }
        Map<UUID, Integer> gaByType = items.stream()
                .filter(item -> item.getSeatId() == null)
                .collect(Collectors.groupingBy(OrderItem::getTicketTypeId, TreeMap::new,
                        Collectors.summingInt(OrderItem::getQuantity)));
        gaByType.forEach(gaInventory::release);
        seatHolds.release(order.getId());
    }
}
