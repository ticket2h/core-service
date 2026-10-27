package com.ticket2h.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.ticket2h.CoreServiceApplication;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Api;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.FakeGateway;
import com.ticket2h.support.FakeIdentity;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.SseClient;
import com.ticket2h.support.TestData.EventRef;
import com.ticket2h.support.TestKeys;

import tools.jackson.databind.JsonNode;

/**
 * M5: "ghế đổi trạng thái hiện ở máy khác dưới 1 giây, kể cả khi hai máy nối vào hai instance khác nhau" (ADR-024).
 * Đi qua HTTP thật: SSE đọc như trình duyệt, đặt vé bằng {@code POST /orders}.
 */
class SeatMapLiveIT extends IntegrationTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);

    @Autowired private StringRedisTemplate redis;
    @Autowired private PostgreSQLContainer postgres;
    @Autowired private KafkaContainer kafka;
    @Autowired @Qualifier("redis") private GenericContainer<?> redisContainer;

    @Test
    void seat_held_by_someone_else_turns_grey_within_a_second_and_back_when_released() {
        EventRef event = testData.eventOnSale(e -> { });
        UUID type = testData.seatedTicketType(event, new BigDecimal("500000"));
        List<UUID> seats = testData.seats(event, type, 3);
        UUID buyer = testData.user();

        try (SseClient viewer = SseClient.open(port, "/events/" + event.id() + "/seats/stream", json)) {
            assertThat(viewer.status()).isEqualTo(200);
            viewer.next("hello", ONE_SECOND);

            String orderId = placeOrder(api, buyer, event, type, seats.subList(0, 2));
            JsonNode held = viewer.next("seats", ONE_SECOND).data();

            assertThat(changes(held)).containsOnlyKeys(seats.get(0), seats.get(1));
            assertThat(changes(held).values()).extracting(c -> c.get("status").asString()).containsOnly("HELD");
            assertThat(changes(held).values()).extracting(c -> c.get("version").asLong()).containsOnly(1L);
            assertThat(changes(held).values()).extracting(c -> c.get("section").asString()).containsOnly("S");

            assertThat(api.post("/orders/" + orderId + "/cancel", testData.token(buyer), Map.of()).status())
                    .isEqualTo(200);
            JsonNode released = viewer.next("seats", ONE_SECOND).data();

            assertThat(changes(released).values()).extracting(c -> c.get("status").asString())
                    .containsOnly("AVAILABLE");
            assertThat(changes(released).values()).extracting(c -> c.get("version").asLong()).containsOnly(2L);
            assertThat(released.get("seq").asLong()).isGreaterThan(held.get("seq").asLong());
        }
    }

    @Test
    void many_changes_in_a_burst_arrive_in_a_few_batches_not_one_message_each() throws Exception {
        EventRef event = testData.eventOnSale(e -> { });
        UUID type = testData.seatedTicketType(event, new BigDecimal("300000"));
        List<UUID> seats = testData.seats(event, type, 30);
        List<UUID> buyers = testData.users(30);

        try (SseClient viewer = SseClient.open(port, "/events/" + event.id() + "/seats/stream", json)) {
            viewer.next("hello", ONE_SECOND);
            Concurrency.run(30, 30, i -> placeOrder(api, buyers.get(i), event, type, List.of(seats.get(i))));
            List<SseClient.Event> received = viewer.collect(Duration.ofSeconds(2));

            Set<UUID> heldSeats = new HashSet<>();
            received.stream().filter(e -> e.name().equals("seats")).forEach(e -> heldSeats.addAll(
                    changes(e.data()).keySet()));
            assertThat(heldSeats).containsExactlyInAnyOrderElementsOf(seats);
            assertThat(received).filteredOn(e -> e.name().equals("seats"))
                    .as("30 ghế đổi gần như cùng lúc, gửi theo lô 300 ms").hasSizeLessThanOrEqualTo(10);
        }
    }

    @Test
    void snapshot_carries_seat_versions_sequence_and_etag() {
        EventRef event = testData.eventOnSale(e -> { });
        UUID type = testData.seatedTicketType(event, new BigDecimal("300000"));
        List<UUID> seats = testData.seats(event, type, 2);
        String path = "/events/" + event.id() + "/seats";

        try (SseClient viewer = SseClient.open(port, path + "/stream", json)) {
            viewer.next("hello", ONE_SECOND);
            Api.Response before = api.get(path, null);
            assertThat(before.status()).isEqualTo(200);
            assertThat(before.json().get(0).get("version").asLong()).isZero();
            String etag = before.header("ETag");
            assertThat(before.header("Cache-Control")).isEqualTo("no-cache");

            Api.Response unchanged = api.get(path, null, Map.of("If-None-Match", etag));
            assertThat(unchanged.status()).as("không đổi: 304, không tải lại cả sơ đồ").isEqualTo(304);

            placeOrder(api, testData.user(), event, type, List.of(seats.get(0)));
            long seq = viewer.next("seats", ONE_SECOND).data().get("seq").asLong();
            Api.Response after = api.get(path, null, Map.of("If-None-Match", etag));

            assertThat(after.status()).isEqualTo(200);
            assertThat(after.header("ETag")).isNotEqualTo(etag);
            assertThat(Long.parseLong(after.header("Seat-Map-Seq")))
                    .as("ảnh chụp đã có thay đổi vừa phát").isGreaterThanOrEqualTo(seq);
            JsonNode seat = find(after.json(), seats.get(0));
            assertThat(seat.get("status").asString()).isEqualTo("HELD");
            assertThat(seat.get("version").asLong()).isEqualTo(1);
        }
    }

    @Test
    void viewer_on_one_instance_sees_changes_made_on_another() {
        EventRef event = testData.eventOnSale(e -> { });
        UUID type = testData.seatedTicketType(event, new BigDecimal("500000"));
        List<UUID> seats = testData.seats(event, type, 2);
        UUID buyer = testData.user();

        try (ConfigurableApplicationContext instanceB = startSecondInstance()) {
            int portB = ((WebServerApplicationContext) instanceB).getWebServer().getPort();
            Api apiB = new Api(portB, json);
            try (SseClient onA = SseClient.open(port, "/events/" + event.id() + "/seats/stream", json);
                    SseClient onB = SseClient.open(portB, "/events/" + event.id() + "/seats/stream", json)) {
                onA.next("hello", ONE_SECOND);
                onB.next("hello", ONE_SECOND);

                String orderId = placeOrder(api, buyer, event, type, seats);   // giữ ghế ở A
                assertThat(changes(onB.next("seats", ONE_SECOND).data()).values())
                        .extracting(c -> c.get("status").asString()).containsOnly("HELD");
                onA.next("seats", ONE_SECOND);   // A cũng nhận (qua Redis như B)

                assertThat(apiB.post("/orders/" + orderId + "/cancel", testData.token(buyer), Map.of()).status())
                        .as("trả ghế ở B").isEqualTo(200);
                assertThat(changes(onA.next("seats", ONE_SECOND).data()).values())
                        .extracting(c -> c.get("status").asString()).containsOnly("AVAILABLE");
            }
        }
    }

    @Test
    void instance_that_missed_messages_asks_browsers_to_reload_the_snapshot() {
        EventRef event = testData.eventOnSale(e -> { });

        try (SseClient viewer = SseClient.open(port, "/events/" + event.id() + "/seats/stream", json)) {
            long seq = viewer.next("hello", ONE_SECOND).data().get("seq").asLong();
            String channel = "t2h:seatmap:changes:" + event.id();
            redis.convertAndSend(channel, (seq + 1) + "|{\"eventId\":\"" + event.id() + "\",\"changes\":[]}");
            redis.convertAndSend(channel, (seq + 5) + "|{\"eventId\":\"" + event.id() + "\",\"changes\":[]}");

            assertThat(viewer.next("resync", ONE_SECOND).data().get("seq").asLong()).isEqualTo(seq + 5);
        }
    }

    @Test
    void selling_goes_on_while_redis_does_not_answer() {
        EventRef event = testData.eventOnSale(e -> { });
        UUID type = testData.seatedTicketType(event, new BigDecimal("500000"));
        List<UUID> seats = testData.seats(event, type, 2);
        var docker = redisContainer.getDockerClient();

        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            long started = System.nanoTime();
            placeOrder(api, testData.user(), event, type, List.of(seats.get(0)));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("không chờ Redis")
                    .isLessThan(Duration.ofSeconds(1));

            Api.Response snapshot = api.get("/events/" + event.id() + "/seats", null);
            assertThat(snapshot.status()).isEqualTo(200);
            assertThat(find(snapshot.json(), seats.get(0)).get("status").asString()).isEqualTo("HELD");
        } finally {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }

        try (SseClient viewer = SseClient.open(port, "/events/" + event.id() + "/seats/stream", json)) {
            viewer.next("hello", Duration.ofSeconds(3));
            placeOrder(api, testData.user(), event, type, List.of(seats.get(1)));
            assertThat(changes(viewer.next("seats", Duration.ofSeconds(3)).data())).containsKey(seats.get(1));
        }
    }

    @Test
    void draft_events_have_no_seat_map() {
        EventRef draft = testData.eventOnSale(e -> e.draft());

        assertThat(api.get("/events/" + draft.id() + "/seats", null).status()).isEqualTo(404);
        try (SseClient viewer = SseClient.open(port, "/events/" + draft.id() + "/seats/stream", json)) {
            assertThat(viewer.status()).isEqualTo(404);
        }
    }

    private String placeOrder(Api client, UUID buyer, EventRef event, UUID type, List<UUID> seatIds) {
        Api.Response response = client.post("/orders", testData.token(buyer),
                CreateOrderRequest.seated(event.id(), type, seatIds),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json().get("orderId").asString();
    }

    private static Map<UUID, JsonNode> changes(JsonNode batch) {
        Map<UUID, JsonNode> bySeat = new HashMap<>();
        batch.get("changes").forEach(c -> bySeat.put(UUID.fromString(c.get("seatId").asString()), c));
        return bySeat;
    }

    private static JsonNode find(JsonNode seats, UUID seatId) {
        for (JsonNode seat : seats) {
            if (seat.get("seatId").asString().equals(seatId.toString())) {
                return seat;
            }
        }
        throw new AssertionError("Không có ghế " + seatId);
    }

    /**
     * Instance thứ hai của core: cùng PostgreSQL, Kafka, Redis, cổng HTTP khác. Tham số dòng lệnh đè cả
     * application.yml. Không nghe Kafka (khỏi chia partition với instance của test), giờ là giờ thật.
     */
    private ConfigurableApplicationContext startSecondInstance() {
        Map<String, String> args = new HashMap<>();
        args.put("server.port", "0");
        args.put("spring.datasource.url", postgres.getJdbcUrl());
        args.put("spring.datasource.username", postgres.getUsername());
        args.put("spring.datasource.password", postgres.getPassword());
        args.put("spring.kafka.bootstrap-servers", kafka.getBootstrapServers());
        args.put("spring.kafka.listener.auto-startup", "false");
        args.put("spring.data.redis.host", redisContainer.getHost());
        args.put("spring.data.redis.port", String.valueOf(redisContainer.getMappedPort(6379)));
        args.put("ticket2h.payment.mockpay.base-url", FakeGateway.INSTANCE.baseUrl());
        args.put("ticket2h.jwt.jwk-set-uri", FakeIdentity.INSTANCE.jwkSetUri());
        args.put("ticket2h.identity-api.base-url", FakeIdentity.INSTANCE.baseUrl());
        args.put("ticket2h.qr.active-key-id", "k1");
        args.put("ticket2h.qr.private-key", TestKeys.privateBase64(TestKeys.QR_K1));
        args.put("ticket2h.qr.public-keys.k1", TestKeys.publicBase64(TestKeys.QR_K1));
        return new SpringApplicationBuilder(CoreServiceApplication.class)
                .profiles("test")
                .run(args.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue())
                        .toArray(String[]::new));
    }
}
