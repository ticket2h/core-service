package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticket2h.catalog.SaleNotOpenException;
import com.ticket2h.inventory.SeatUnavailableException;
import com.ticket2h.inventory.SoldOutException;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.ordering.CreateOrderRequest.Item;
import com.ticket2h.ordering.IdempotencyKeyReusedException;
import com.ticket2h.ordering.OrderPlacement;
import com.ticket2h.ordering.OrderPlacement.PlacementResult;
import com.ticket2h.ordering.OrderStatus;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.Invariants;

/** Bước 6: tạo đơn qua service (không qua HTTP) để chạy nhanh và dễ đếm. */
class OrderPlacementIT extends IntegrationTest {

    @Autowired OrderPlacement placement;

    @Test
    void two_seats_and_two_ga_tickets_make_one_pending_order() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        List<UUID> seats = testData.seats(event, vip, 2);
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 100);
        UUID buyer = testData.user();

        PlacementResult result = placement.place(buyer, "key-1",
                CreateOrderRequest.of(event.id(), Item.seats(vip, seats), Item.ga(ga, 2)));

        assertThat(result.replayed()).isFalse();
        assertThat(result.order().getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(result.order().getTotalAmount()).isEqualByComparingTo("4600000");
        assertThat(result.order().getExpiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(10)));
        assertThat(jdbc.queryForList("SELECT status FROM seats WHERE id IN (?, ?)", String.class,
                seats.get(0), seats.get(1))).containsOnly("HELD");
        assertThat(available(ga)).isEqualTo(98);
        assertThat(reserved(buyer, event.id())).isEqualTo(4);
        Invariants.assertConsistent(jdbc);
    }

    @Test
    void one_taken_seat_rolls_back_everything() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        List<UUID> seats = testData.seats(event, vip, 3);
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 100);
        placement.place(testData.user(), "other", CreateOrderRequest.seated(event.id(), vip, List.of(seats.get(1))));
        UUID buyer = testData.user();

        assertThatThrownBy(() -> placement.place(buyer, "key-1",
                CreateOrderRequest.of(event.id(), Item.seats(vip, seats), Item.ga(ga, 1))))
                .isInstanceOf(SeatUnavailableException.class)
                .satisfies(e -> assertThat(((SeatUnavailableException) e).unavailableSeatIds())
                        .containsExactly(seats.get(1)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, buyer)).isZero();
        assertThat(available(ga)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM purchase_quotas WHERE user_id = ?", Integer.class, buyer))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM seats WHERE status = 'HELD'", Integer.class)).isEqualTo(1);
    }

    @Test
    void same_key_and_same_body_returns_the_same_order() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 100);
        UUID buyer = testData.user();

        PlacementResult first = placement.place(buyer, "key-1", CreateOrderRequest.ga(event.id(), ga, 2));
        PlacementResult second = placement.place(buyer, "key-1", CreateOrderRequest.ga(event.id(), ga, 2));

        assertThat(second.replayed()).isTrue();
        assertThat(second.order().getId()).isEqualTo(first.order().getId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(available(ga)).isEqualTo(98);
    }

    @Test
    void same_key_with_different_body_is_rejected() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 100);
        UUID buyer = testData.user();
        placement.place(buyer, "key-1", CreateOrderRequest.ga(event.id(), ga, 2));

        assertThatThrownBy(() -> placement.place(buyer, "key-1", CreateOrderRequest.ga(event.id(), ga, 3)))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void same_key_from_different_users_creates_two_orders() {
        var event = testData.eventOnSale(e -> {});
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 100);

        placement.place(testData.user(), "shared-key", CreateOrderRequest.ga(event.id(), ga, 1));
        placement.place(testData.user(), "shared-key", CreateOrderRequest.ga(event.id(), ga, 1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(2);
    }

    @Test
    void twenty_parallel_requests_with_the_same_key_create_exactly_one_order() throws Exception {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        List<UUID> seats = testData.seats(event, vip, 2);
        UUID buyer = testData.user();
        CreateOrderRequest request = CreateOrderRequest.seated(event.id(), vip, seats);

        List<UUID> orderIds = Concurrency.run(20, 20,
                i -> placement.place(buyer, "same-key", request).order().getId());

        assertThat(orderIds).hasSize(20).containsOnly(orderIds.getFirst());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders", Integer.class)).isEqualTo(1);
        Invariants.assertConsistent(jdbc);
    }

    @Test
    void outside_the_sale_window_is_rejected() {
        var event = testData.eventOnSale(e -> e.saleWindow(clock.instant().plus(Duration.ofHours(1)),
                clock.instant().plus(Duration.ofDays(1))));
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 100);

        assertThatThrownBy(() -> placement.place(testData.user(), "k", CreateOrderRequest.ga(event.id(), ga, 1)))
                .isInstanceOf(SaleNotOpenException.class);

        clock.advance(Duration.ofDays(2));   // đã đóng bán
        assertThatThrownBy(() -> placement.place(testData.user(), "k2", CreateOrderRequest.ga(event.id(), ga, 1)))
                .isInstanceOf(SaleNotOpenException.class);
    }

    @Test
    void more_than_the_per_user_limit_in_one_order_is_rejected() {
        var event = testData.eventOnSale(e -> e.maxTicketsPerUser(4));
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 100);

        assertThatThrownBy(() -> placement.place(testData.user(), "k", CreateOrderRequest.ga(event.id(), ga, 5)))
                .isInstanceOf(com.ticket2h.inventory.PurchaseLimitExceededException.class);
        assertThat(available(ga)).isEqualTo(100);
    }

    /** Tiêu chí M2: 10.000 lượt đặt, 2.500 user (mỗi user 4 lượt, không ai vượt hạn mức), đúng 1.000 vé được giữ. */
    @Test
    @Tag("slow")
    void ten_thousand_orders_for_one_thousand_ga_tickets() throws Exception {
        var event = testData.eventOnSale(e -> e.maxTicketsPerUser(4));
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 1_000);
        List<UUID> buyers = testData.users(2_500);

        List<String> outcomes = Concurrency.run(10_000, 64, i -> {
            try {
                placement.place(buyers.get(i % buyers.size()), UUID.randomUUID().toString(),
                        CreateOrderRequest.ga(event.id(), ga, 1));
                return "OK";
            } catch (SoldOutException e) {
                return "SOLD_OUT";
            }
        });

        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1_000);
        assertThat(jdbc.queryForObject("""
                SELECT coalesce(sum(oi.quantity), 0) FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                WHERE o.event_id = ? AND o.status = 'PENDING_PAYMENT'
                """, Integer.class, event.id())).isEqualTo(1_000);
        Invariants.assertConsistent(jdbc);
    }

    private int available(UUID ticketTypeId) {
        return jdbc.queryForObject("SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class,
                ticketTypeId);
    }

    private int reserved(UUID buyer, UUID eventId) {
        return jdbc.queryForObject("SELECT reserved FROM purchase_quotas WHERE user_id = ? AND event_id = ?",
                Integer.class, buyer, eventId);
    }
}
