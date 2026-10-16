package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticket2h.inventory.InvalidSeatException;
import com.ticket2h.inventory.SeatHoldService;
import com.ticket2h.inventory.SeatUnavailableException;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;

/** Bước 3. Mọi exception khác SeatUnavailableException (deadlock, lỗi SQL) ném ra khỏi Concurrency.run và làm test đỏ. */
class SeatClaimConcurrencyIT extends IntegrationTest {

    @Autowired SeatHoldService seatHolds;
    @Autowired TransactionTemplate tx;

    @Test
    void only_one_of_200_concurrent_buyers_gets_the_same_seat() throws Exception {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        UUID seatA12 = testData.seats(event, vip, 1).getFirst();
        List<UUID> orders = testData.pendingOrders(event, 200);

        List<Boolean> results = Concurrency.run(200, 200, i -> {
            try {
                tx.executeWithoutResult(s -> seatHolds.hold(orders.get(i), event.id(),
                        List.of(seatA12), clock.instant(), clock.instant().plus(Duration.ofMinutes(10))));
                return true;
            } catch (SeatUnavailableException e) {
                return false;
            }
        });

        assertThat(results).filteredOn(ok -> ok).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM seats WHERE id = ?", String.class, seatA12)).isEqualTo("HELD");
    }

    @Test
    void overlapping_multi_seat_holds_never_deadlock() throws Exception {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));

        for (int round = 0; round < 50; round++) {
            List<UUID> seats = testData.seats(event, vip, 2);
            List<UUID> forward = List.of(seats.get(0), seats.get(1));
            List<UUID> backward = List.of(seats.get(1), seats.get(0));
            List<UUID> orders = testData.pendingOrders(event, 2);

            List<Boolean> results = Concurrency.run(2, 2, i -> {
                try {
                    tx.executeWithoutResult(s -> seatHolds.hold(orders.get(i), event.id(),
                            i == 0 ? forward : backward, clock.instant(),
                            clock.instant().plus(Duration.ofMinutes(10))));
                    return true;
                } catch (SeatUnavailableException e) {
                    return false;
                }
            });

            assertThat(results).containsExactlyInAnyOrder(true, false);
        }
    }

    /**
     * Job dọn đơn trả ghế của đơn A trong lúc người C giữ lại chính các ghế đó (đã quá hạn). Cả hai bên
     * phải khoá ghế theo cùng thứ tự id, nếu không PostgreSQL báo deadlock và một bên nhận lỗi 500.
     */
    @Test
    void releasing_expired_seats_while_someone_retakes_them_never_deadlocks() throws Exception {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        for (int round = 0; round < 50; round++) {
            List<UUID> seats = testData.seats(event, vip, 4);
            List<UUID> orders = testData.pendingOrders(event, 2);
            UUID orderA = orders.get(0);
            UUID orderC = orders.get(1);
            tx.executeWithoutResult(s -> seatHolds.hold(orderA, event.id(), seats, clock.instant(),
                    clock.instant().plus(Duration.ofMinutes(10))));
            clock.advance(Duration.ofMinutes(11));   // ghế của A đã quá hạn, job chưa dọn
            List<UUID> reversed = seats.reversed();

            Concurrency.run(2, 2, i -> {
                if (i == 0) {
                    tx.executeWithoutResult(s -> seatHolds.release(orderA));
                } else {
                    tx.executeWithoutResult(s -> seatHolds.hold(orderC, event.id(), reversed, clock.instant(),
                            clock.instant().plus(Duration.ofMinutes(10))));
                }
                return null;
            });

            assertThat(jdbc.queryForList("SELECT DISTINCT order_id FROM seats WHERE id IN (?, ?, ?, ?)", UUID.class,
                    seats.get(0), seats.get(1), seats.get(2), seats.get(3))).containsExactly(orderC);
        }
    }

    @Test
    void seat_held_past_its_expiry_can_be_taken_by_someone_else() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        UUID seat = testData.seats(event, vip, 1).getFirst();
        List<UUID> orders = testData.pendingOrders(event, 2);

        tx.executeWithoutResult(s -> seatHolds.hold(orders.get(0), event.id(), List.of(seat), clock.instant(),
                clock.instant().plus(Duration.ofMinutes(10))));
        // Còn hạn: người thứ hai không giữ được
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> seatHolds.hold(orders.get(1), event.id(),
                List.of(seat), clock.instant(), clock.instant().plus(Duration.ofMinutes(10)))))
                .isInstanceOf(SeatUnavailableException.class);

        // Quá hạn, job chưa dọn: người thứ hai giữ được ngay (hết hạn lười)
        clock.advance(Duration.ofMinutes(11));
        tx.executeWithoutResult(s -> seatHolds.hold(orders.get(1), event.id(), List.of(seat), clock.instant(),
                clock.instant().plus(Duration.ofMinutes(10))));

        assertThat(jdbc.queryForObject("SELECT order_id FROM seats WHERE id = ?", UUID.class, seat))
                .isEqualTo(orders.get(1));
    }

    @Test
    void seat_of_another_event_is_rejected_as_invalid() {
        var event = testData.eventOnSale(e -> {});
        var otherEvent = testData.eventOnSale(e -> {});
        UUID otherVip = testData.seatedTicketType(otherEvent, new BigDecimal("1500000"));
        UUID foreignSeat = testData.seats(otherEvent, otherVip, 1).getFirst();
        UUID order = testData.pendingOrders(event, 1).getFirst();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> seatHolds.hold(order, event.id(),
                List.of(foreignSeat), clock.instant(), clock.instant().plus(Duration.ofMinutes(10)))))
                .isInstanceOf(InvalidSeatException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, foreignSeat))
                .isEqualTo("AVAILABLE");
    }

    @Test
    void duplicate_seat_ids_are_rejected() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        UUID seat = testData.seats(event, vip, 1).getFirst();
        UUID order = testData.pendingOrders(event, 1).getFirst();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> seatHolds.hold(order, event.id(),
                List.of(seat, seat), clock.instant(), clock.instant().plus(Duration.ofMinutes(10)))))
                .isInstanceOf(InvalidSeatException.class);
    }
}
