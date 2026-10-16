package com.ticket2h.ordering;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Body của {@code POST /orders}. Không có trường giá: giá do server quyết định. Client gửi kèm
 * {@code price} thì Jackson bỏ qua trường lạ.
 */
public record CreateOrderRequest(
        @NotNull UUID eventId,
        @NotEmpty @Size(max = 10) List<@Valid @NotNull Item> items) {

    /** SEATED: có {@code seatIds}, không có {@code quantity}. GA: có {@code quantity}, không có {@code seatIds}. */
    public record Item(
            @NotNull UUID ticketTypeId,
            @Size(max = 10) List<@NotNull UUID> seatIds,
            @Positive @Max(20) Integer quantity) {

        public boolean hasSeats() {
            return seatIds != null && !seatIds.isEmpty();
        }

        public static Item seats(UUID ticketTypeId, List<UUID> seatIds) {
            return new Item(ticketTypeId, List.copyOf(seatIds), null);
        }

        public static Item ga(UUID ticketTypeId, int quantity) {
            return new Item(ticketTypeId, null, quantity);
        }
    }

    public static CreateOrderRequest of(UUID eventId, Item... items) {
        return new CreateOrderRequest(eventId, Arrays.asList(items));
    }

    public static CreateOrderRequest ga(UUID eventId, UUID ticketTypeId, int quantity) {
        return of(eventId, Item.ga(ticketTypeId, quantity));
    }

    public static CreateOrderRequest seated(UUID eventId, UUID ticketTypeId, List<UUID> seatIds) {
        return of(eventId, Item.seats(ticketTypeId, seatIds));
    }
}
