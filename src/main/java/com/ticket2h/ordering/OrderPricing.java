package com.ticket2h.ordering;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.catalog.EventSnapshot;
import com.ticket2h.catalog.TicketKind;
import com.ticket2h.catalog.TicketTypeInfo;
import com.ticket2h.common.error.ValidationException;
import com.ticket2h.inventory.InvalidSeatException;

/**
 * Kiểm tra và định giá request, ngoài khoá (chỉ đọc). Giá lấy từ {@code ticket_types.price},
 * không bao giờ nhận giá từ client.
 */
@Component
class OrderPricing {

    private final CatalogQueries catalog;

    OrderPricing(CatalogQueries catalog) {
        this.catalog = catalog;
    }

    PricedOrder price(EventSnapshot event, CreateOrderRequest request) {
        Map<UUID, TicketTypeInfo> types = catalog.ticketTypes(event.id()).stream()
                .collect(Collectors.toMap(TicketTypeInfo::id, Function.identity()));

        Set<UUID> seenTypes = new HashSet<>();
        Set<UUID> seenSeats = new HashSet<>();
        List<CreateOrderRequest.Item> seatedItems = new ArrayList<>();
        List<PricedOrder.GaLine> gaLines = new ArrayList<>();

        for (CreateOrderRequest.Item item : request.items()) {
            if (!seenTypes.add(item.ticketTypeId())) {
                throw new ValidationException("Mỗi loại vé chỉ được xuất hiện một lần trong đơn");
            }
            TicketTypeInfo type = types.get(item.ticketTypeId());
            if (type == null) {
                throw new ValidationException("Loại vé không thuộc sự kiện này");
            }
            if (type.kind() == TicketKind.SEATED) {
                if (!item.hasSeats() || item.quantity() != null) {
                    throw new ValidationException("Vé " + type.name() + " cần seatIds và không nhận quantity");
                }
                for (UUID seatId : item.seatIds()) {
                    if (!seenSeats.add(seatId)) {
                        throw new InvalidSeatException("Danh sách ghế bị trùng");
                    }
                }
                seatedItems.add(item);
            } else {
                if (item.quantity() == null || item.quantity() < 1 || item.hasSeats()) {
                    throw new ValidationException("Vé " + type.name() + " cần quantity >= 1 và không nhận seatIds");
                }
                gaLines.add(new PricedOrder.GaLine(type.id(), item.quantity(), type.price()));
            }
        }

        List<PricedOrder.SeatLine> seatLines = new ArrayList<>();
        if (!seatedItems.isEmpty()) {
            Map<UUID, UUID> seatTypes = catalog.seatTicketTypes(event.id(), List.copyOf(seenSeats));
            for (CreateOrderRequest.Item item : seatedItems) {
                TicketTypeInfo type = types.get(item.ticketTypeId());
                for (UUID seatId : item.seatIds()) {
                    UUID actualType = seatTypes.get(seatId);
                    if (actualType == null) {
                        throw new InvalidSeatException("Có ghế không thuộc sự kiện này");
                    }
                    if (!actualType.equals(type.id())) {
                        throw new InvalidSeatException("Có ghế không thuộc loại vé " + type.name());
                    }
                    seatLines.add(new PricedOrder.SeatLine(type.id(), seatId, type.price()));
                }
            }
        }
        return new PricedOrder(seatLines, gaLines);
    }
}
