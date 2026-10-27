package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.testcontainers.containers.GenericContainer;

import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Api;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;
import com.ticket2h.support.TestData.EventSpec;
import com.ticket2h.waitingroom.WaitingRoom;

import tools.jackson.databind.JsonNode;

/**
 * M5: "phòng chờ ảo: mọi người xếp hàng, mỗi giây cho N người vào mua, ai cũng thấy mình đang đứng thứ mấy" (ADR-027).
 * Job cho người vào tắt trong test: gọi {@link WaitingRoom#admitDue()} bằng tay, mỗi lần một giây theo {@code clock}.
 */
class WaitingRoomIT extends IntegrationTest {

    @Autowired private WaitingRoom waitingRoom;
    @Autowired @Qualifier("redis") private GenericContainer<?> redisContainer;

    @Test
    void events_without_a_waiting_room_sell_directly() {
        EventRef event = testData.eventOnSale(e -> { });
        UUID type = testData.gaTicketType(event, new BigDecimal("100000"), 10);
        UUID buyer = testData.user();

        assertThat(status(buyer, event).get("state").asString()).isEqualTo("NOT_REQUIRED");
        assertThat(api.get("/events/" + event.id(), null).json().get("waitingRoom").asBoolean()).isFalse();
        assertThat(order(buyer, event, type, null).status()).isEqualTo(201);
    }

    @Test
    void buying_needs_an_admission_token_and_people_are_let_in_first_come_first_served() {
        EventRef event = eventWithWaitingRoom(2, e -> { });
        UUID type = testData.gaTicketType(event, new BigDecimal("100000"), 100);
        List<UUID> buyers = testData.users(5);

        assertThat(api.get("/events/" + event.id(), null).json().get("waitingRoom").asBoolean()).isTrue();
        Api.Response direct = order(buyers.get(0), event, type, null);
        assertThat(direct.status()).isEqualTo(403);
        assertThat(direct.json().get("code").asString()).isEqualTo("ADMISSION_REQUIRED");

        List<Long> positions = new ArrayList<>();
        for (UUID buyer : buyers) {
            positions.add(join(buyer, event).get("position").asLong());
        }
        assertThat(positions).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(join(buyers.get(0), event).get("position").asLong()).as("vào lại vẫn giữ chỗ").isEqualTo(1);
        JsonNode third = status(buyers.get(2), event);
        assertThat(third.get("waiting").asLong()).isEqualTo(5);
        assertThat(third.get("estimatedWaitSeconds").asLong()).as("3 người, 2 người mỗi giây").isEqualTo(2);
        assertThat(third.get("pollAfterSeconds").asInt()).isBetween(2, 15);

        assertThat(waitingRoom.admitDue()).isEqualTo(2);

        JsonNode first = status(buyers.get(0), event);
        assertThat(first.get("state").asString()).isEqualTo("ADMITTED");
        String token = first.get("admissionToken").asString();
        assertThat(status(buyers.get(2), event).get("position").asLong()).as("thứ tự giảm dần").isEqualTo(1);
        assertThat(order(buyers.get(0), event, type, token).status()).isEqualTo(201);
        assertThat(order(buyers.get(2), event, type, token).status()).as("vé vào cửa của người khác").isEqualTo(403);
        assertThat(order(buyers.get(0), event, type, token.substring(0, token.length() - 2) + "xx").status())
                .as("sửa chữ ký").isEqualTo(403);
    }

    @Test
    void n_people_per_second_even_when_every_instance_runs_the_job() {
        EventRef event = eventWithWaitingRoom(3, e -> { });
        testData.users(10).forEach(buyer -> join(buyer, event));

        assertThat(waitingRoom.admitDue()).isEqualTo(3);
        assertThat(waitingRoom.admitDue()).as("instance khác chạy job trong cùng giây").isZero();
        clock.advance(Duration.ofSeconds(1));
        assertThat(waitingRoom.admitDue()).isEqualTo(3);
        clock.advance(Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(1));
        assertThat(waitingRoom.admitDue()).as("không dồn giây đã qua").isEqualTo(3);
        clock.advance(Duration.ofSeconds(1));
        assertThat(waitingRoom.admitDue()).isEqualTo(1);
    }

    @Test
    void people_arriving_before_the_sale_opens_are_shuffled_and_stay_ahead_of_later_arrivals() {
        EventRef event = eventWithWaitingRoom(100, e -> e.saleWindow(START.plus(Duration.ofMinutes(10)),
                START.plus(Duration.ofDays(1))));
        List<UUID> early = testData.users(30);
        List<Long> joinOrder = new ArrayList<>();
        for (UUID buyer : early) {
            JsonNode joined = join(buyer, event);
            assertThat(joined.get("estimatedWaitSeconds").asLong()).as("chờ tới giờ mở bán").isGreaterThan(590);
            joinOrder.add((long) joinOrder.size() + 1);
        }

        List<Long> positions = early.stream().map(b -> status(b, event).get("position").asLong()).toList();
        assertThat(positions).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 30).boxed().toList());
        assertThat(positions).as("không theo thứ tự bấm").isNotEqualTo(joinOrder);

        clock.set(START.plus(Duration.ofMinutes(10)).plusSeconds(1));
        assertThat(join(testData.user(), event).get("position").asLong()).isEqualTo(31);
    }

    @Test
    void people_who_stop_asking_for_their_position_lose_their_place() {
        EventRef event = eventWithWaitingRoom(1, e -> { });
        List<UUID> buyers = testData.users(3);
        buyers.forEach(buyer -> join(buyer, event));

        clock.advance(Duration.ofSeconds(40));
        status(buyers.get(1), event);
        status(buyers.get(2), event);
        clock.advance(Duration.ofSeconds(21));   // người thứ nhất im lặng 61 giây: đã đóng tab
        assertThat(waitingRoom.admitDue()).isEqualTo(1);

        assertThat(status(buyers.get(0), event).get("state").asString()).isEqualTo("NOT_IN_QUEUE");
        assertThat(status(buyers.get(1), event).get("state").asString()).isEqualTo("ADMITTED");
        assertThat(status(buyers.get(2), event).get("position").asLong()).isEqualTo(1);
    }

    @Test
    void admission_expires_after_ten_minutes() {
        EventRef event = eventWithWaitingRoom(5, e -> { });
        UUID type = testData.gaTicketType(event, new BigDecimal("100000"), 10);
        UUID buyer = testData.user();
        join(buyer, event);
        waitingRoom.admitDue();
        String token = status(buyer, event).get("admissionToken").asString();

        clock.advance(Duration.ofMinutes(10));

        assertThat(order(buyer, event, type, token).status()).isEqualTo(403);
        assertThat(status(buyer, event).get("state").asString()).isEqualTo("NOT_IN_QUEUE");
        assertThat(join(buyer, event).get("state").asString()).as("xếp hàng lại").isEqualTo("WAITING");
    }

    @Test
    void two_hundred_people_joining_at_once_get_distinct_places() throws Exception {
        EventRef event = eventWithWaitingRoom(50, e -> { });
        List<UUID> buyers = testData.users(200);

        List<Long> positions = Concurrency.run(200, 50, i -> join(buyers.get(i), event).get("position").asLong());

        assertThat(positions).containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 200).boxed().toList());
    }

    @Test
    void organizer_turns_the_waiting_room_on_and_watches_the_queue() {
        EventRef event = testData.eventOnSale(e -> { });
        String organizer = testData.token(event.organizer().userId());
        String path = "/organizer/events/" + event.id() + "/waiting-room";

        Api.Response on = api.put(path, organizer, Map.of("admitPerSecond", 200));
        assertThat(on.status()).isEqualTo(200);
        assertThat(on.json().get("waitingRoomAdmitPerSecond").asInt()).isEqualTo(200);
        assertThat(api.put(path, organizer, Map.of("admitPerSecond", 0)).status()).isEqualTo(400);
        assertThat(api.put(path, testData.token(testData.organizer().userId()), Map.of("admitPerSecond", 5)).status())
                .as("ban tổ chức khác").isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT details->>'to' FROM audit_logs WHERE action = 'WAITING_ROOM_CHANGED'",
                String.class)).isEqualTo("200");

        testData.users(3).forEach(buyer -> join(buyer, event));
        JsonNode stats = api.get(path, organizer).json();
        assertThat(stats.get("waiting").asLong()).isEqualTo(3);
        assertThat(stats.get("admitPerSecond").asInt()).isEqualTo(200);

        Map<String, Object> off = new HashMap<>();
        off.put("admitPerSecond", null);
        assertThat(api.put(path, organizer, off).json().get("waitingRoomAdmitPerSecond").isNull()).isTrue();
        UUID type = testData.gaTicketType(event, new BigDecimal("100000"), 10);
        assertThat(order(testData.user(), event, type, null).status()).as("tắt: đặt thẳng").isEqualTo(201);
    }

    @Test
    void queue_opens_thirty_minutes_before_the_sale() {
        EventRef event = eventWithWaitingRoom(10, e -> e.saleWindow(START.plus(Duration.ofHours(2)),
                START.plus(Duration.ofDays(1))));

        Api.Response early = api.post("/events/" + event.id() + "/waiting-room", testData.token(testData.user()),
                Map.of());

        assertThat(early.status()).isEqualTo(409);
        assertThat(early.json().get("code").asString()).isEqualTo("WAITING_ROOM_NOT_OPEN");
    }

    @Test
    void people_already_admitted_can_still_buy_while_redis_does_not_answer() {
        EventRef event = eventWithWaitingRoom(5, e -> { });
        UUID type = testData.gaTicketType(event, new BigDecimal("100000"), 10);
        UUID admitted = testData.user();
        join(admitted, event);
        waitingRoom.admitDue();
        String token = status(admitted, event).get("admissionToken").asString();
        var docker = redisContainer.getDockerClient();

        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            Api.Response join = api.post("/events/" + event.id() + "/waiting-room", testData.token(testData.user()),
                    Map.of());
            assertThat(join.status()).isEqualTo(503);
            assertThat(join.json().get("code").asString()).isEqualTo("WAITING_ROOM_UNAVAILABLE");
            assertThat(order(admitted, event, type, token).status()).isEqualTo(201);
        } finally {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
    }

    private EventRef eventWithWaitingRoom(int admitPerSecond, Consumer<EventSpec> customizer) {
        EventRef event = testData.eventOnSale(customizer);
        jdbc.update("UPDATE events SET waiting_room_admit_per_second = ? WHERE id = ?", admitPerSecond, event.id());
        return event;
    }

    private JsonNode join(UUID buyer, EventRef event) {
        Api.Response response = api.post("/events/" + event.id() + "/waiting-room", testData.token(buyer), Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private JsonNode status(UUID buyer, EventRef event) {
        Api.Response response = api.get("/events/" + event.id() + "/waiting-room", testData.token(buyer));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private Api.Response order(UUID buyer, EventRef event, UUID type, String admissionToken) {
        Map<String, String> headers = new HashMap<>(Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        if (admissionToken != null) {
            headers.put("Admission-Token", admissionToken);
        }
        return api.post("/orders", testData.token(buyer), CreateOrderRequest.ga(event.id(), type, 1), headers);
    }
}
