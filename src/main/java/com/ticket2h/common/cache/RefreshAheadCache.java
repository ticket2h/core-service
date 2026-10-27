package com.ticket2h.common.cache;

import java.time.Duration;
import java.util.function.Function;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * Cache trong bộ nhớ của một instance, làm mới ở nền (ADR-025). Sau {@code refreshAfter}, request kế tiếp vẫn nhận
 * ngay bản đang có, còn Caffeine tải bản mới trên một luồng nền (mỗi khoá một lần tải dù bao nhiêu request). Chỉ khi
 * chưa có gì (lần đầu) thì request mới phải chờ tải, và các request cùng khoá chờ chung một lần tải đó.
 * <p>
 * Vì sao không dùng {@code @Cacheable(sync = true)} với thời hạn ngắn: mỗi lần hết hạn, MỌI request của khoá đó phải
 * chờ lần tải lại; lúc database đang bận (giờ mở bán) lần tải chờ kết nối, và trang sự kiện vốn đã có trong bộ nhớ
 * cũng chậm theo (đo bằng load test: p95 trên 2 giây).
 * <p>
 * Tải lại lỗi (database chậm, lỗi tạm) thì giữ bản cũ và thử lại ở request sau; quá {@code maxStale} chưa tải lại được
 * thì bỏ bản cũ, request phải chờ tải như lần đầu. Lỗi của lần tải đầu (ví dụ 404) ném thẳng cho người gọi, không nhớ.
 * {@code refreshAfter} bằng 0: không cache (profile test, để đọc lại được ngay sau khi đổi).
 */
public final class RefreshAheadCache<K, V> {

    private final LoadingCache<K, V> cache;
    private final Function<K, V> loader;

    RefreshAheadCache(Duration refreshAfter, Duration maxStale, long maxWeight, Function<V, Integer> weight,
            Function<K, V> loader) {
        this.loader = loader;
        this.cache = refreshAfter.isZero() ? null : Caffeine.newBuilder()
                .refreshAfterWrite(refreshAfter)
                .expireAfterWrite(maxStale)
                .maximumWeight(maxWeight)
                .weigher((K key, V value) -> weight.apply(value))
                .recordStats()
                .build(loader::apply);
    }

    public V get(K key) {
        return cache == null ? loader.apply(key) : cache.get(key);
    }

    CacheStats stats() {
        return cache == null ? CacheStats.empty() : cache.stats();
    }

    LoadingCache<K, V> nativeCache() {
        return cache;
    }
}
