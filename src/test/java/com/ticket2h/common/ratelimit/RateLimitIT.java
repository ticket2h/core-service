package com.ticket2h.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ticket2h.common.config.Ticket2hProperties.RateLimit.Bucket;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.support.Api;
import com.ticket2h.support.Concurrency;
import com.ticket2h.support.IntegrationTest;
import com.ticket2h.support.TestData.EventRef;

/** Giới hạn request (ADR-026): token bucket trên Redis, 429 kèm Retry-After. */
class RateLimitIT extends IntegrationTest {

    @Autowired private RateLimiter limiter;
    @Autowired private StringRedisTemplate redis;

    @Test
    void bucket_allows_a_burst_then_one_request_per_refill_period() {
        String key = "test:" + UUID.randomUUID();
        Bucket bucket = new Bucket(3, 1.0);

        List<Boolean> allowed = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            allowed.add(limiter.tryAcquire(key, bucket).allowed());
        }

        assertThat(allowed).containsExactly(true, true, true, false);
        RateLimiter.Decision rejected = limiter.tryAcquire(key, bucket);
        assertThat(rejected.retryAfter().toMillis()).isBetween(1L, 1000L);
    }

    @Test
    void two_instances_hitting_the_same_bucket_never_let_more_through_than_its_capacity() throws Exception {
        String key = "test:" + UUID.randomUUID();
        Bucket bucket = new Bucket(50, 0.001);

        List<Boolean> results = Concurrency.run(500, 50, i -> limiter.tryAcquire(key, bucket).allowed());

        assertThat(results).filteredOn(Boolean::booleanValue).hasSize(50);
    }

    @Test
    void buyer_sending_orders_too_fast_gets_429_with_retry_after() {
        EventRef event = testData.eventOnSale(e -> { });
        UUID type = testData.gaTicketType(event, new BigDecimal("100000"), 100);
        UUID buyer = testData.user();
        drain("orders:user:" + buyer);

        Api.Response response = order(buyer, event, type);

        assertThat(response.status()).isEqualTo(429);
        assertThat(response.json().get("code").asString()).isEqualTo("TOO_MANY_REQUESTS");
        assertThat(Long.parseLong(response.header("Retry-After"))).isPositive();
        assertThat(response.json().get("retryAfterSeconds").asLong()).isPositive();
        assertThat(order(testData.user(), event, type).status()).as("người khác vẫn đặt được").isEqualTo(201);
    }

    @Test
    void one_ip_flooding_the_api_is_limited_without_affecting_other_ips() {
        EventRef event = testData.eventOnSale(e -> { });
        drain("ip:203.0.113.7");

        assertThat(api.get("/events/" + event.id(), null, Map.of("X-Forwarded-For", "203.0.113.7")).status())
                .isEqualTo(429);
        assertThat(api.get("/events/" + event.id(), null, Map.of("X-Forwarded-For", "203.0.113.8")).status())
                .isEqualTo(200);
    }

    @Test
    void payment_webhooks_are_never_rate_limited() {
        drain("ip:127.0.0.1");
        try {
            assertThat(api.postRaw("/webhooks/payments/mockpay", "{}".getBytes(), Map.of()).status())
                    .as("chữ ký sai: 401, không phải 429").isEqualTo(401);
            assertThat(api.get("/events", null).status()).isEqualTo(429);
        } finally {
            redis.delete(RateLimiter.KEY_PREFIX + "ip:127.0.0.1");
        }
    }

    /** Xô hết lượt, đầy lại 1 lượt mỗi giây (cấu hình của profile test): request ngay sau đó bị chặn. */
    private void drain(String key) {
        redis.opsForHash().putAll(RateLimiter.KEY_PREFIX + key, Map.of("tokens", "0", "at",
                String.valueOf(redisNowMillis())));
    }

    private long redisNowMillis() {
        return redis.execute((org.springframework.data.redis.core.RedisCallback<Long>) c -> c.serverCommands().time());
    }

    private Api.Response order(UUID buyer, EventRef event, UUID type) {
        return api.post("/orders", testData.token(buyer), CreateOrderRequest.ga(event.id(), type, 1),
                Map.of("Idempotency-Key", UUID.randomUUID().toString()));
    }
}
