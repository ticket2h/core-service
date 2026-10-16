package com.ticket2h.ordering;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Request đã kiểm tra và định giá theo giá trong database. */
record PricedOrder(List<SeatLine> seatLines, List<GaLine> gaLines) {

    record SeatLine(UUID ticketTypeId, UUID seatId, BigDecimal unitPrice) {}

    record GaLine(UUID ticketTypeId, int quantity, BigDecimal unitPrice) {}

    int totalQuantity() {
        return seatLines.size() + gaLines.stream().mapToInt(GaLine::quantity).sum();
    }

    BigDecimal total() {
        BigDecimal total = BigDecimal.ZERO;
        for (SeatLine line : seatLines) {
            total = total.add(line.unitPrice());
        }
        for (GaLine line : gaLines) {
            total = total.add(line.unitPrice().multiply(BigDecimal.valueOf(line.quantity())));
        }
        return total.setScale(2, RoundingMode.UNNECESSARY);
    }

    /** Thứ tự khoá ticket_types: theo id tăng dần (cùng thứ tự với OrderRelease). */
    List<GaLine> gaLinesSortedByTicketType() {
        return gaLines.stream().sorted(Comparator.comparing(GaLine::ticketTypeId)).toList();
    }

    /** Ghế của mọi loại vé, giữ trong một lần gọi để khoá đúng thứ tự id. */
    List<UUID> allSeatIds() {
        return seatLines.stream().map(SeatLine::seatId).toList();
    }

    List<OrderItem> toItems(UUID orderId) {
        List<OrderItem> items = new ArrayList<>();
        for (SeatLine line : seatLines) {
            items.add(new OrderItem(orderId, line.ticketTypeId(), line.seatId(), 1, line.unitPrice()));
        }
        for (GaLine line : gaLines) {
            items.add(new OrderItem(orderId, line.ticketTypeId(), null, line.quantity(), line.unitPrice()));
        }
        return items;
    }
}
