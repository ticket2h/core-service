package com.ticket2h.common.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/** ADR-025: hết hạn làm mới thì vẫn trả ngay bản cũ, tải bản mới ở nền. */
class RefreshAheadCacheTest {

    private final AtomicInteger version = new AtomicInteger();

    @Test
    void after_the_refresh_interval_callers_get_the_old_value_at_once_while_the_new_one_loads() throws Exception {
        AtomicBoolean slow = new AtomicBoolean();
        RefreshAheadCache<String, Integer> cache = cache(Duration.ofMillis(50), key -> {
            if (slow.get()) {
                sleep(500);   // database đang bận
            }
            return version.incrementAndGet();
        });
        assertThat(cache.get("e1")).isEqualTo(1);

        slow.set(true);
        Thread.sleep(80);
        long started = System.nanoTime();
        int value = cache.get("e1");

        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("không chờ lần tải lại")
                .isLessThan(Duration.ofMillis(100));
        assertThat(value).isEqualTo(1);
        await().atMost(Duration.ofSeconds(2)).until(() -> cache.get("e1") == 2);
    }

    @Test
    void a_failed_refresh_keeps_the_old_value() throws Exception {
        AtomicBoolean failing = new AtomicBoolean();
        RefreshAheadCache<String, Integer> cache = cache(Duration.ofMillis(50), key -> {
            if (failing.get()) {
                throw new IllegalStateException("database không trả lời");
            }
            return version.incrementAndGet();
        });
        cache.get("e1");

        failing.set(true);
        Thread.sleep(80);

        assertThat(cache.get("e1")).isEqualTo(1);
        Thread.sleep(50);
        assertThat(cache.get("e1")).isEqualTo(1);
    }

    @Test
    void first_load_errors_reach_the_caller_and_are_not_remembered() {
        AtomicBoolean missing = new AtomicBoolean(true);
        RefreshAheadCache<String, Integer> cache = cache(Duration.ofMinutes(1), key -> {
            if (missing.get()) {
                throw new IllegalArgumentException("không có sự kiện");
            }
            return version.incrementAndGet();
        });

        assertThatThrownBy(() -> cache.get("e1")).isInstanceOf(IllegalArgumentException.class);
        missing.set(false);
        assertThat(cache.get("e1")).isEqualTo(1);
    }

    @Test
    void zero_interval_means_no_cache() {
        RefreshAheadCache<String, Integer> cache = cache(Duration.ZERO, key -> version.incrementAndGet());

        assertThat(cache.get("e1")).isEqualTo(1);
        assertThat(cache.get("e1")).isEqualTo(2);
    }

    private static <V> RefreshAheadCache<String, V> cache(Duration refresh, Function<String, V> load) {
        return new RefreshAheadCache<>(refresh, Duration.ofMinutes(5), 1_000, value -> 1, load);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
