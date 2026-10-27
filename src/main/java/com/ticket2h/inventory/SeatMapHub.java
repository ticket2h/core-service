package com.ticket2h.inventory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

/**
 * Các kết nối SSE sơ đồ ghế đang mở trên instance này (ADR-024). Thay đổi nhận từ {@link SeatChangeBus} được gom theo
 * sự kiện và gửi một lần mỗi {@code flush-interval} (300 ms): 1.000 ghế đổi trong một giây thành khoảng 3 message cho
 * mỗi trình duyệt, không phải 1.000. Trong một lô, mỗi ghế chỉ giữ thay đổi có {@code version} lớn nhất.
 * <p>
 * Event SSE gửi cho trình duyệt (body JSON):
 * <ul>
 *   <li>{@code hello} ngay khi nối: {@code {"seq": n}}. Ảnh chụp tải sau đó phải có {@code seq >= n}, không thì có
 *       thể thiếu thay đổi xảy ra giữa lúc chụp và lúc nối: tải lại.</li>
 *   <li>{@code seats}: {@code {"seq": n, "changes": [{seatId, section, status, version}]}}. Chỉ áp thay đổi có
 *       {@code version} lớn hơn {@code version} của ghế đang hiển thị.</li>
 *   <li>{@code resync}: {@code {"seq": n}}. Instance này đã lỡ message (số thứ tự nhảy cóc): tải lại ảnh chụp có
 *       {@code seq >= n}.</li>
 * </ul>
 * Mỗi kết nối gửi lần lượt trên một luồng ảo riêng: trình duyệt chậm không làm chậm những trình duyệt khác. Chậm quá
 * (dồn quá {@value #MAX_QUEUED} message) thì đóng kết nối; trình duyệt tự nối lại và tải lại ảnh chụp.
 */
@Component
public class SeatMapHub implements DisposableBean {

    static final int MAX_QUEUED = 50;

    private static final Logger log = LoggerFactory.getLogger(SeatMapHub.class);

    private final Map<UUID, Set<Stream>> streams = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    /** Số thứ tự lớn nhất đã nhận, chỉ cho sự kiện đang có người xem trên instance này. */
    private final Map<UUID, AtomicLong> lastSeq = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private final Ticket2hProperties.SeatMap settings;
    private final JsonMapper json;
    private final ScheduledExecutorService scheduler;

    SeatMapHub(Ticket2hProperties properties, JsonMapper json) {
        this.settings = properties.seatMap();
        this.json = json;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("seat-map-hub").daemon().factory());
        long flush = settings.flushInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::flushQuietly, flush, flush, TimeUnit.MILLISECONDS);
        long heartbeat = settings.heartbeat().toMillis();
        scheduler.scheduleWithFixedDelay(this::heartbeat, heartbeat, heartbeat, TimeUnit.MILLISECONDS);
    }

    /**
     * Mở một kết nối. Đăng ký kết nối TRƯỚC rồi mới đọc {@code currentSeq}: message nào có số lớn hơn số gửi trong
     * {@code hello} chắc chắn tới được kết nối này.
     */
    SseEmitter subscribe(UUID eventId, LongSupplier currentSeq) {
        if (open.incrementAndGet() > settings.maxStreams()) {
            open.decrementAndGet();
            throw new BusinessException(ErrorCode.SEAT_MAP_STREAM_UNAVAILABLE,
                    "Mỗi instance mở tối đa " + settings.maxStreams() + " kết nối sơ đồ ghế");
        }
        SseEmitter emitter = new SseEmitter(settings.streamTimeout().toMillis());
        Stream stream = new Stream(eventId, emitter);
        emitter.onCompletion(() -> remove(stream));
        emitter.onTimeout(() -> remove(stream));
        emitter.onError(e -> remove(stream));
        // compute nguyên khối theo sự kiện: không chen được vào giữa lúc remove() bỏ tập kết nối vừa rỗng
        streams.compute(eventId, (id, set) -> {
            Set<Stream> next = set == null ? ConcurrentHashMap.newKeySet() : set;
            next.add(stream);
            return next;
        });
        long seq = currentSeq.getAsLong();
        lastSeq.computeIfAbsent(eventId, id -> new AtomicLong()).accumulateAndGet(seq, Math::max);
        stream.send("hello", json.writeValueAsString(Map.of("seq", seq)));
        return emitter;
    }

    /** Thay đổi từ Redis (mọi instance đều nhận mọi sự kiện). Không ai xem sự kiện này trên instance này thì bỏ. */
    void accept(UUID eventId, long seq, List<SeatChange> changes) {
        if (!streams.containsKey(eventId)) {
            return;
        }
        long previous = lastSeq.computeIfAbsent(eventId, id -> new AtomicLong()).getAndAccumulate(seq, Math::max);
        // previous = 0: chưa biết số trước đó (Redis vừa khởi động lại, hoặc chưa ai phát) nên không kết luận được
        boolean missed = previous > 0 && seq > previous + 1;
        if (missed) {
            log.info("Sơ đồ ghế sự kiện {}: lỡ message {}..{}, yêu cầu trình duyệt tải lại", eventId, previous + 1,
                    seq - 1);
        }
        pending.computeIfAbsent(eventId, id -> new Pending()).add(seq, changes, missed);
    }

    /** Số kết nối đang mở trên instance này. */
    int openStreams() {
        return open.get();
    }

    /** Gửi các lô đang chờ (lịch chạy mỗi {@code flush-interval}; test gọi thẳng). */
    void flush() {
        for (UUID eventId : List.copyOf(pending.keySet())) {
            Pending batch = pending.remove(eventId);
            Set<Stream> subscribers = streams.get(eventId);
            if (batch == null || subscribers == null || subscribers.isEmpty()) {
                continue;
            }
            Drained drained = batch.drain();
            String seats = drained.changes().isEmpty() ? null
                    : json.writeValueAsString(new Batch(drained.seq(), drained.changes()));
            String resync = drained.resync() ? json.writeValueAsString(Map.of("seq", drained.seq())) : null;
            for (Stream stream : subscribers) {
                if (seats != null) {
                    stream.send("seats", seats);
                }
                if (resync != null) {
                    stream.send("resync", resync);
                }
            }
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException e) {
            log.error("Lỗi khi gửi thay đổi sơ đồ ghế", e);
        }
    }

    /** Dòng chú thích giữ kết nối qua proxy, và để phát hiện trình duyệt đã đi (gửi lỗi thì đóng kết nối). */
    private void heartbeat() {
        try {
            streams.values().forEach(set -> set.forEach(Stream::keepAlive));
        } catch (RuntimeException e) {
            log.warn("Lỗi khi gửi keepalive sơ đồ ghế: {}", e.toString());
        }
    }

    private void remove(Stream stream) {
        boolean[] removed = {false};
        streams.computeIfPresent(stream.eventId, (id, set) -> {
            removed[0] = set.remove(stream);
            if (!set.isEmpty()) {
                return set;
            }
            lastSeq.remove(id);
            pending.remove(id);
            return null;
        });
        if (removed[0]) {
            open.decrementAndGet();
            stream.close();
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
        streams.values().forEach(set -> set.forEach(stream -> stream.emitter.complete()));
    }

    /** Thay đổi đang chờ gửi của một sự kiện. */
    private static final class Pending {
        private final Map<UUID, SeatChange> latest = new LinkedHashMap<>();
        private long seq;
        private boolean resync;

        synchronized void add(long newSeq, List<SeatChange> changes, boolean missed) {
            resync |= missed;
            seq = Math.max(seq, newSeq);
            for (SeatChange change : changes) {
                latest.merge(change.seatId(), change, (a, b) -> b.version() >= a.version() ? b : a);
            }
        }

        synchronized Drained drain() {
            return new Drained(seq, new ArrayList<>(latest.values()), resync);
        }
    }

    private record Drained(long seq, List<SeatChange> changes, boolean resync) {}

    /** Body của event {@code seats}. */
    record Batch(long seq, List<SeatChange> changes) {}

    /** Một kết nối: gửi lần lượt trên một luồng ảo riêng. */
    private final class Stream {
        private final UUID eventId;
        private final SseEmitter emitter;
        private final ExecutorService sender = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
        private final AtomicInteger queued = new AtomicInteger();

        Stream(UUID eventId, SseEmitter emitter) {
            this.eventId = eventId;
            this.emitter = emitter;
        }

        void send(String name, String data) {
            // Builder không dùng lại được (build() ghi thêm vào nó), mỗi kết nối một builder; chuỗi JSON thì dùng chung
            submit(() -> SseEmitter.event().name(name).data(data));
        }

        void keepAlive() {
            submit(() -> SseEmitter.event().comment("keepalive"));
        }

        private void submit(Supplier<SseEmitter.SseEventBuilder> event) {
            if (queued.incrementAndGet() > MAX_QUEUED) {
                queued.decrementAndGet();
                log.info("Đóng kết nối sơ đồ ghế quá chậm của sự kiện {}", eventId);
                emitter.complete();
                remove(this);
                return;
            }
            try {
                sender.execute(() -> {
                    try {
                        emitter.send(event.get());
                    } catch (IOException | IllegalStateException e) {
                        // Trình duyệt đã đóng kết nối, hoặc kết nối vừa hết hạn
                        remove(this);
                    } finally {
                        queued.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException e) {
                queued.decrementAndGet();   // kết nối vừa đóng
            }
        }

        void close() {
            sender.shutdown();
        }
    }
}
