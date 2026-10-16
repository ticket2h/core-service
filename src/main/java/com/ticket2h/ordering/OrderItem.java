package com.ticket2h.ordering;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.ticket2h.common.persistence.AssignedIdEntity;

/** Một dòng của đơn: một ghế (quantity = 1) hoặc một số vé GA. Không đổi sau khi tạo. */
@Entity
@Table(name = "order_items")
public class OrderItem extends AssignedIdEntity {

    private UUID orderId;
    private UUID ticketTypeId;
    private UUID seatId;
    private int quantity;
    private BigDecimal unitPrice;

    protected OrderItem() {}

    OrderItem(UUID orderId, UUID ticketTypeId, UUID seatId, int quantity, BigDecimal unitPrice) {
        super(UUID.randomUUID());
        this.orderId = orderId;
        this.ticketTypeId = ticketTypeId;
        this.seatId = seatId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getTicketTypeId() {
        return ticketTypeId;
    }

    public UUID getSeatId() {
        return seatId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
