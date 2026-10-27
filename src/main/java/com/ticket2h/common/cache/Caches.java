package com.ticket2h.common.cache;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.ticket2h.common.config.Ticket2hProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;

/**
 * Nơi tạo các {@link RefreshAheadCache} (ADR-025): đặt tên, gắn số liệu trúng/trượt vào {@code /actuator/metrics}
 * ({@code cache.gets}, {@code cache.loads}), cho test đọc thống kê.
 */
@Component
public class Caches {

    /** Chi tiết sự kiện công khai, khoá là id sự kiện. */
    public static final String EVENT_DETAIL = "event-detail";
    /** Ảnh chụp sơ đồ ghế đã đổi sẵn sang JSON, khoá là id sự kiện. */
    public static final String SEAT_MAP = "seat-map";
    /** Sự kiện (giờ bán, có phòng chờ không) cho phòng chờ: mỗi lần hỏi thứ tự và mỗi đơn đều cần. */
    public static final String WAITING_ROOM_EVENTS = "waiting-room-events";

    private final Ticket2hProperties.Cache settings;
    private final MeterRegistry meters;
    private final Map<String, RefreshAheadCache<?, ?>> byName = new ConcurrentHashMap<>();

    Caches(Ticket2hProperties properties, MeterRegistry meters) {
        this.settings = properties.cache();
        this.meters = meters;
    }

    /** Chi tiết sự kiện: tối đa 10.000 sự kiện. */
    public <K, V> RefreshAheadCache<K, V> eventDetail(Function<K, V> loader) {
        return create(EVENT_DETAIL, settings.eventTtl(), 10_000, value -> 1, loader);
    }

    /** Sự kiện cho phòng chờ: cùng thời hạn với chi tiết sự kiện. */
    public <K, V> RefreshAheadCache<K, V> waitingRoomEvents(Function<K, V> loader) {
        return create(WAITING_ROOM_EVENTS, settings.eventTtl(), 10_000, value -> 1, loader);
    }

    /** Sơ đồ ghế: giới hạn theo tổng kích thước JSON ({@code seat-map-max-size}). */
    public <K, V> RefreshAheadCache<K, V> seatMap(Function<V, Integer> bytes, Function<K, V> loader) {
        return create(SEAT_MAP, settings.seatMapTtl(), settings.seatMapMaxSize().toBytes(), bytes, loader);
    }

    private <K, V> RefreshAheadCache<K, V> create(String name, Duration refreshAfter, long maxWeight,
            Function<V, Integer> weight, Function<K, V> loader) {
        RefreshAheadCache<K, V> cache = new RefreshAheadCache<>(refreshAfter, settings.maxStale(), maxWeight, weight,
                loader);
        if (byName.putIfAbsent(name, cache) != null) {
            throw new IllegalStateException("Cache " + name + " đã được tạo");
        }
        if (cache.nativeCache() != null) {
            CaffeineCacheMetrics.monitor(meters, cache.nativeCache(), name);
        }
        return cache;
    }

    /** Thống kê trúng/trượt/số lần tải của một cache (test). */
    public CacheStats stats(String name) {
        return byName.get(name).stats();
    }
}
