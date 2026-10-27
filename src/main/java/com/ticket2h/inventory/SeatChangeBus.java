package com.ticket2h.inventory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.ticket2h.common.redis.RedisBreaker;

import tools.jackson.databind.json.JsonMapper;

/**
 * Kênh phát thay đổi ghế giữa mọi instance core qua Redis pub/sub (ADR-024). Instance nào giữ, trả, bán ghế thì phát;
 * mọi instance (kể cả chính nó) nhận rồi chuyển cho {@link SeatMapHub}, hub đẩy cho trình duyệt đang nối vào mình.
 * <p>
 * Phát trên một luồng riêng, không trên luồng request: Redis chậm hay sập không làm chậm việc đặt vé. Luồng này lấy
 * hết những gì đang chờ rồi gộp theo sự kiện, nên lúc đông một message chở nhiều ghế. Hàng đợi đầy (Redis sập lâu) thì
 * bỏ thay đổi và ghi log: thay đổi đã commit, trình duyệt thấy ở lần tải lại ảnh chụp định kỳ.
 * <p>
 * Mỗi message có số thứ tự tăng dần theo sự kiện ({@code seq}). Tăng số và phát nằm trong MỘT script Lua, Redis chạy
 * script nguyên khối, nên mọi instance nhận message theo đúng thứ tự số. Hub thấy số nhảy cóc (mất kết nối Redis giữa
 * chừng) thì bảo trình duyệt tải lại ảnh chụp; ảnh chụp mang số đọc trước khi đọc database ({@link SeatMapQueries}).
 */
@Component
class SeatChangeBus implements MessageListener, DisposableBean {

    static final String CHANNEL_PREFIX = "t2h:seatmap:changes:";
    static final String CHANNEL_PATTERN = CHANNEL_PREFIX + "*";
    private static final String SEQUENCE_PREFIX = "t2h:seatmap:seq:";
    private static final int QUEUE_CAPACITY = 10_000;
    private static final int DRAIN_LIMIT = 1_000;

    private static final RedisScript<Long> PUBLISH = RedisScript.of("""
            local seq = redis.call('INCR', KEYS[1])
            redis.call('PUBLISH', KEYS[2], seq .. '|' .. ARGV[1])
            return seq
            """, Long.class);

    private static final Logger log = LoggerFactory.getLogger(SeatChangeBus.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final SeatMapHub hub;
    private final RedisBreaker breaker;
    private final BlockingQueue<Outgoing> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread publisher;
    private volatile boolean running = true;

    SeatChangeBus(StringRedisTemplate redis, JsonMapper json, SeatMapHub hub, RedisBreaker breaker) {
        this.redis = redis;
        this.json = json;
        this.hub = hub;
        this.breaker = breaker;
        this.publisher = Thread.ofPlatform().name("seat-change-publisher").daemon().start(this::publishLoop);
    }

    /** Gọi sau khi commit ({@link SeatChangeRecorder}). Không chờ Redis. */
    void publish(UUID eventId, List<SeatChange> changes) {
        if (!queue.offer(new Outgoing(eventId, changes))) {
            log.warn("Hàng đợi thay đổi ghế đầy, bỏ {} thay đổi của sự kiện {}", changes.size(), eventId);
        }
    }

    /** Số thứ tự của message mới nhất đã phát cho sự kiện; 0 nếu chưa có hoặc Redis không trả lời. */
    long currentSequence(UUID eventId) {
        return breaker.call("số thứ tự sơ đồ ghế", () -> {
            String value = redis.opsForValue().get(SEQUENCE_PREFIX + eventId);
            return value == null ? 0L : Long.parseLong(value);
        }, () -> 0L);
    }

    /** Message từ Redis: {@code <seq>|<json>}. */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        int separator = body.indexOf('|');
        try {
            long seq = Long.parseLong(body.substring(0, separator));
            Payload payload = json.readValue(body.substring(separator + 1), Payload.class);
            hub.accept(payload.eventId(), seq, payload.changes());
        } catch (RuntimeException e) {
            log.warn("Bỏ qua message thay đổi ghế không đọc được: {}", e.toString());
        }
    }

    private void publishLoop() {
        List<Outgoing> batch = new ArrayList<>();
        while (running) {
            try {
                Outgoing first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, DRAIN_LIMIT);
                Map<UUID, List<SeatChange>> byEvent = new LinkedHashMap<>();
                for (Outgoing outgoing : batch) {
                    byEvent.computeIfAbsent(outgoing.eventId(), id -> new ArrayList<>()).addAll(outgoing.changes());
                }
                byEvent.forEach(this::send);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                batch.clear();
            }
        }
    }

    private void send(UUID eventId, List<SeatChange> changes) {
        try {
            redis.execute(PUBLISH, List.of(SEQUENCE_PREFIX + eventId, CHANNEL_PREFIX + eventId),
                    json.writeValueAsString(new Payload(eventId, changes)));
        } catch (RuntimeException e) {
            log.warn("Không phát được {} thay đổi ghế của sự kiện {} (Redis?): {}", changes.size(), eventId,
                    e.toString());
        }
    }

    @Override
    public void destroy() throws InterruptedException {
        running = false;
        publisher.join(2_000);
    }

    private record Outgoing(UUID eventId, List<SeatChange> changes) {}

    record Payload(UUID eventId, List<SeatChange> changes) {}
}
