package com.ticket2h.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ticket2h.ordering.CreateOrderRequest.Item;

class RequestHashTest {

    private final UUID event = UUID.randomUUID();
    private final UUID vip = UUID.randomUUID();
    private final UUID ga = UUID.randomUUID();
    private final UUID seatA = UUID.randomUUID();
    private final UUID seatB = UUID.randomUUID();

    @Test
    void order_of_items_and_seats_does_not_matter() {
        String one = RequestHash.of(CreateOrderRequest.of(event, Item.seats(vip, List.of(seatA, seatB)), Item.ga(ga, 2)));
        String two = RequestHash.of(CreateOrderRequest.of(event, Item.ga(ga, 2), Item.seats(vip, List.of(seatB, seatA))));

        assertThat(one).isEqualTo(two).hasSize(64);
    }

    @Test
    void different_quantity_or_seat_gives_different_hash() {
        String base = RequestHash.of(CreateOrderRequest.ga(event, ga, 2));

        assertThat(RequestHash.of(CreateOrderRequest.ga(event, ga, 3))).isNotEqualTo(base);
        assertThat(RequestHash.of(CreateOrderRequest.seated(event, vip, List.of(seatA))))
                .isNotEqualTo(RequestHash.of(CreateOrderRequest.seated(event, vip, List.of(seatB))));
    }
}
