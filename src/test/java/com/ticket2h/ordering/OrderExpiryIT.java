package com.ticket2h.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.ticket2h.ordering.CreateOrderRequest.Item;
import com.ticket2h.support.Api;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.Invariants;

/** Bước 7. Scheduler tắt trong test: tua đồng hồ rồi gọi sweep() trực tiếp. */
class OrderExpiryIT extends IntegrationTest {

    @Autowired OrderExpiryJob expiryJob;

    @Test
    void expired_order_past_grace_releases_seats_ga_and_quota() {
        Fixture f = fixture();
        UUID orderId = testData.placeOrder(f.buyer, f.request()).order().getId();

        clock.advance(Duration.ofMinutes(12));   // 10 phút giữ chỗ + 1 phút grace + 1
        assertThat(expiryJob.sweep()).isEqualTo(1);

        assertThat(status(orderId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForList("SELECT status FROM seats WHERE event_id = ?", String.class, f.event.id()))
                .containsOnly("AVAILABLE");
        assertThat(available(f.ga)).isEqualTo(10);
        assertThat(reserved(f.buyer, f.event.id())).isZero();
        Invariants.assertConsistent(jdbc);
    }

    @Test
    void expired_order_within_grace_is_left_alone() {
        Fixture f = fixture();
        UUID orderId = testData.placeOrder(f.buyer, f.request()).order().getId();

        clock.advance(Duration.ofMinutes(10).plusSeconds(30));
        assertThat(expiryJob.sweep()).isZero();

        assertThat(status(orderId)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void seat_retaken_by_someone_else_stays_theirs_when_the_job_expires_the_old_order() {
        Fixture f = fixture();
        UUID orderA = testData.placeOrder(f.buyer, f.request()).order().getId();

        clock.advance(Duration.ofMinutes(11));   // A quá hạn, job chưa chạy
        UUID buyerB = testData.user();
        UUID orderB = testData.placeOrder(buyerB, CreateOrderRequest.seated(f.event.id(), f.vip, List.of(f.seats.get(0))))
                .order().getId();

        clock.advance(Duration.ofMinutes(2));
        expiryJob.sweep();

        assertThat(status(orderA)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT order_id FROM seats WHERE id = ?", UUID.class, f.seats.get(0)))
                .isEqualTo(orderB);
        assertThat(jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, f.seats.get(1)))
                .isEqualTo("AVAILABLE");
        Invariants.assertConsistent(jdbc);
    }

    @Test
    void running_the_job_twice_does_nothing_the_second_time() {
        Fixture f = fixture();
        testData.placeOrder(f.buyer, f.request());

        clock.advance(Duration.ofMinutes(12));
        assertThat(expiryJob.sweep()).isEqualTo(1);
        assertThat(expiryJob.sweep()).isZero();

        assertThat(available(f.ga)).isEqualTo(10);   // không cộng hai lần
    }

    @Test
    void customer_cancels_pending_order_and_everything_is_released() {
        Fixture f = fixture();
        UUID orderId = testData.placeOrder(f.buyer, f.request()).order().getId();

        Api.Response response = api.post("/orders/" + orderId + "/cancel", testData.token(f.buyer), null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(available(f.ga)).isEqualTo(10);
        assertThat(reserved(f.buyer, f.event.id())).isZero();
        assertThat(jdbc.queryForList("SELECT status FROM seats WHERE event_id = ?", String.class, f.event.id()))
                .containsOnly("AVAILABLE");
    }

    @Test
    void cancelling_a_paid_order_is_409() {
        Fixture f = fixture();
        UUID orderId = testData.paidOrderViaWebhook(f.buyer, f.request());

        Api.Response response = api.post("/orders/" + orderId + "/cancel", testData.token(f.buyer), null);

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("ORDER_NOT_CANCELLABLE");   // đơn PAID hoặc đã FULFILLED
    }

    @Test
    void cancelling_someone_elses_order_is_404() {
        Fixture f = fixture();
        UUID orderId = testData.placeOrder(f.buyer, f.request()).order().getId();

        Api.Response response = api.post("/orders/" + orderId + "/cancel", testData.token(testData.user()), null);

        assertThat(response.status()).isEqualTo(404);
        assertThat(status(orderId)).isEqualTo("PENDING_PAYMENT");
    }

    private Fixture fixture() {
        var event = testData.eventOnSale(e -> {});
        UUID vip = testData.seatedTicketType(event, new BigDecimal("1500000"));
        List<UUID> seats = testData.seats(event, vip, 2);
        UUID ga = testData.gaTicketType(event, new BigDecimal("800000"), 10);
        return new Fixture(event, vip, seats, ga, testData.user());
    }

    record Fixture(com.ticket2h.support.TestData.EventRef event, UUID vip, List<UUID> seats, UUID ga, UUID buyer) {
        CreateOrderRequest request() {
            return CreateOrderRequest.of(event.id(), Item.seats(vip, seats), Item.ga(ga, 2));
        }
    }

    private String status(UUID orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    private int available(UUID ga) {
        return jdbc.queryForObject("SELECT available_quantity FROM ticket_types WHERE id = ?", Integer.class, ga);
    }

    private int reserved(UUID buyer, UUID eventId) {
        return jdbc.queryForObject("SELECT reserved FROM purchase_quotas WHERE user_id = ? AND event_id = ?",
                Integer.class, buyer, eventId);
    }
}
