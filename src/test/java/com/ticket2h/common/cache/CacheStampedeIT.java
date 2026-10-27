package com.ticket2h.common.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.ticket2h.support.Api;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

/**
 * Cache của trang sự kiện và sơ đồ ghế lúc mở bán (ADR-025): nhiều người mở cùng lúc, database chỉ nhận một truy vấn.
 * Context riêng, bật lại cache (profile test tắt cache để test khác đọc lại được ngay).
 */
@TestPropertySource(properties = {"ticket2h.cache.event-ttl=PT1M", "ticket2h.cache.seat-map-ttl=PT1M",
        "ticket2h.cache.max-stale=PT2M"})
class CacheStampedeIT extends IntegrationTest {

    @Autowired private Caches caches;

    @Test
    void two_hundred_people_opening_the_seat_map_at_once_cause_one_load() throws Exception {
        EventRef event = testData.eventOnSale(e -> { });
        testData.seats(event, testData.seatedTicketType(event, new BigDecimal("500000")), 500);
        CacheStats before = caches.stats(Caches.SEAT_MAP);

        List<Api.Response> responses = Concurrency.run(200, 50,
                i -> api.get("/events/" + event.id() + "/seats", null));

        assertThat(responses).extracting(Api.Response::status).containsOnly(200);
        assertThat(responses).extracting(r -> r.header("ETag")).containsOnly(responses.getFirst().header("ETag"));
        CacheStats delta = caches.stats(Caches.SEAT_MAP).minus(before);
        assertThat(delta.loadCount()).as("một truy vấn cho 200 request").isEqualTo(1);
        assertThat(delta.hitCount()).isEqualTo(199);
    }

    @Test
    void two_hundred_people_opening_the_event_page_at_once_cause_one_load() throws Exception {
        EventRef event = testData.eventOnSale(e -> { });
        testData.gaTicketType(event, new BigDecimal("300000"), 1000);
        CacheStats before = caches.stats(Caches.EVENT_DETAIL);

        List<Api.Response> responses = Concurrency.run(200, 50, i -> api.get("/events/" + event.id(), null));

        assertThat(responses).extracting(Api.Response::status).containsOnly(200);
        assertThat(caches.stats(Caches.EVENT_DETAIL).minus(before).loadCount()).isEqualTo(1);
    }

    @Test
    void missing_or_draft_events_are_not_cached() {
        EventRef draft = testData.eventOnSale(e -> e.draft());
        assertThat(api.get("/events/" + draft.id(), null).status()).isEqualTo(404);
        assertThat(api.get("/events/" + draft.id() + "/seats", null).status()).isEqualTo(404);

        jdbc.update("UPDATE events SET status = 'PUBLISHED' WHERE id = ?", draft.id());

        assertThat(api.get("/events/" + draft.id(), null).status()).as("404 không bị nhớ").isEqualTo(200);
        assertThat(api.get("/events/" + draft.id() + "/seats", null).status()).isEqualTo(200);
        assertThat(api.get("/events/" + UUID.randomUUID(), null).status()).isEqualTo(404);
    }
}
