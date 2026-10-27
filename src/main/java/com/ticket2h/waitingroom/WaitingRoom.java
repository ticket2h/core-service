package com.ticket2h.waitingroom;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.catalog.EventSnapshot;
import com.ticket2h.catalog.EventStatus;
import com.ticket2h.common.cache.Caches;
import com.ticket2h.common.cache.RefreshAheadCache;
import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.common.error.NotFoundException;

/**
 * Phòng chờ ảo lúc mở bán (ADR-027): mọi người xếp hàng, mỗi giây cho {@code waiting_room_admit_per_second} người
 * vào; người được vào nhận vé vào cửa có chữ ký, API đặt vé chỉ nhận đơn kèm vé đó. Database chỉ chịu số người đặt
 * vé mỗi giây đã chọn trước, không phải số người đang mở trang.
 * <p>
 * Hàng chờ nằm trong Redis. Redis sập: không ai vào hàng thêm được (503), nhưng người đã có vé vào cửa vẫn đặt vé
 * được (kiểm tra vé không cần Redis). Sự kiện không bật phòng chờ không bị ảnh hưởng.
 */
@Service
public class WaitingRoom {

    private static final int MIN_POLL_SECONDS = 2;
    private static final int MAX_POLL_SECONDS = 15;
    /** Hàng chờ còn trong Redis tới ngần này sau giờ đóng bán, rồi Redis tự xoá. */
    private static final Duration KEEP_AFTER_SALE = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(WaitingRoom.class);

    private final CatalogQueries catalog;
    private final WaitingRoomStore store;
    private final AdmissionTokens tokens;
    private final Ticket2hProperties.WaitingRoom settings;
    private final Clock clock;
    /**
     * Mỗi lần hỏi thứ tự và mỗi đơn đều cần biết sự kiện có phòng chờ không và giờ bán: lúc mở bán là hàng nghìn câu
     * SELECT mỗi giây (load test M5: PostgreSQL lên 2,5 CPU chỉ vì việc này). Cache làm mới ở nền: ban tổ chức bật
     * hay tắt phòng chờ thì có hiệu lực sau tối đa khoảng 2 giây.
     */
    private final RefreshAheadCache<UUID, Optional<EventSnapshot>> events;

    WaitingRoom(CatalogQueries catalog, WaitingRoomStore store, AdmissionTokens tokens, Ticket2hProperties properties,
            Clock clock, Caches caches) {
        this.catalog = catalog;
        this.store = store;
        this.tokens = tokens;
        this.settings = properties.waitingRoom();
        this.clock = clock;
        this.events = caches.waitingRoomEvents(catalog::findEvent);
    }

    /** Xếp hàng (gọi lại nhiều lần vẫn giữ chỗ cũ). Mở từ {@code opens-before-sale} trước giờ mở bán tới giờ đóng. */
    public QueueStatus join(UUID userId, UUID eventId) {
        return query(userId, eventId, true);
    }

    /** Hỏi thứ tự; cũng là báo "vẫn đang chờ". */
    public QueueStatus status(UUID userId, UUID eventId) {
        return query(userId, eventId, false);
    }

    private QueueStatus query(UUID userId, UUID eventId, boolean join) {
        EventSnapshot event = publishedEvent(eventId);
        if (!event.hasWaitingRoom()) {
            return QueueStatus.notRequired();
        }
        Instant now = clock.instant();
        Instant opensAt = event.saleOpensAt().minus(settings.opensBeforeSale());
        if (now.isBefore(opensAt) || !now.isBefore(event.saleClosesAt())) {
            throw new BusinessException(ErrorCode.WAITING_ROOM_NOT_OPEN, "Phòng chờ mở từ "
                    + settings.opensBeforeSale().toMinutes() + " phút trước giờ mở bán tới giờ đóng bán");
        }
        WaitingRoomStore.Entry entry;
        try {
            entry = store.joinOrStatus(eventId, userId, join, now.isBefore(event.saleOpensAt()), now,
                    keepFor(event, now));
        } catch (DataAccessException e) {
            log.warn("Phòng chờ sự kiện {} không truy cập được Redis: {}", eventId, e.toString());
            throw new BusinessException(ErrorCode.WAITING_ROOM_UNAVAILABLE, "Phòng chờ tạm thời không hoạt động, "
                    + "hãy thử lại sau ít giây");
        }
        return switch (entry.state()) {
            case ADMITTED -> new QueueStatus(QueueStatus.State.ADMITTED, null, null, 0L, 0,
                    tokens.issue(eventId, userId, entry.admittedUntil()), entry.admittedUntil());
            case NOT_IN_QUEUE -> new QueueStatus(QueueStatus.State.NOT_IN_QUEUE, null, entry.waiting(), null, 0,
                    null, null);
            case WAITING -> waiting(event, entry, now);
        };
    }

    private QueueStatus waiting(EventSnapshot event, WaitingRoomStore.Entry entry, Instant now) {
        long untilOpen = Math.max(0, Duration.between(now, event.saleOpensAt()).toSeconds());
        int rate = event.waitingRoomAdmitPerSecond();
        long estimate = untilOpen + (entry.position() + rate - 1) / rate;
        // Hỏi lại sau khoảng nửa thời gian còn phải chờ: được vào thì có 10 phút, biết trễ vài giây không sao, còn
        // 100.000 người hỏi mỗi 2 giây là 50.000 request mỗi giây chỉ để hỏi thứ tự
        int poll = (int) Math.clamp(estimate / 2, MIN_POLL_SECONDS, MAX_POLL_SECONDS);
        return new QueueStatus(QueueStatus.State.WAITING, entry.position(), entry.waiting(), estimate, poll, null,
                null);
    }

    /**
     * Gọi trước khi đặt vé. Chỉ chặn khi sự kiện có phòng chờ và đang trong giờ bán (ngoài giờ bán thì việc đặt vé
     * tự từ chối với {@code SALE_NOT_OPEN}). Không gọi Redis.
     */
    public void checkAdmission(UUID userId, UUID eventId, String admissionToken) {
        EventSnapshot event = events.get(eventId).orElse(null);
        Instant now = clock.instant();
        if (event == null || !event.hasWaitingRoom() || !event.isOnSale(now)) {
            return;
        }
        if (admissionToken == null || admissionToken.isBlank()) {
            throw new AdmissionRequiredException("Sự kiện này có phòng chờ, hãy xếp hàng trước khi đặt vé");
        }
        if (tokens.verify(admissionToken, eventId, userId, now).isEmpty()) {
            throw new AdmissionRequiredException("Vé vào cửa không hợp lệ hoặc đã hết hạn, hãy xếp hàng lại");
        }
    }

    /** Job mỗi giây: cho người vào ở mọi sự kiện có phòng chờ đang trong giờ bán. Trả tổng số người được vào. */
    public int admitDue() {
        Instant now = clock.instant();
        int admitted = 0;
        for (EventSnapshot event : catalog.eventsWithOpenWaitingRoom(now)) {
            try {
                admitted += (int) Math.max(0, admit(event, now));
            } catch (DataAccessException e) {
                log.warn("Không cho người vào phòng chờ sự kiện {} (Redis?): {}", event.id(), e.toString());
            }
        }
        return admitted;
    }

    private long admit(EventSnapshot event, Instant now) {
        return store.admit(event.id(), event.waitingRoomAdmitPerSecond(), now, now.minus(settings.staleAfter()),
                now.plus(settings.admissionTtl()), keepFor(event, now));
    }

    private static Duration keepFor(EventSnapshot event, Instant now) {
        return Duration.between(now, event.saleClosesAt()).plus(KEEP_AFTER_SALE);
    }

    /** Cho ban tổ chức xem: số người đang chờ, số người đã vào còn hạn. */
    public WaitingRoomStats stats(EventSnapshot event) {
        if (!event.hasWaitingRoom()) {
            return new WaitingRoomStats(null, 0, 0);
        }
        try {
            WaitingRoomStore.Stats stats = store.stats(event.id(), clock.instant());
            return new WaitingRoomStats(event.waitingRoomAdmitPerSecond(), stats.waiting(), stats.admitted());
        } catch (DataAccessException e) {
            throw new BusinessException(ErrorCode.WAITING_ROOM_UNAVAILABLE, "Không đọc được phòng chờ");
        }
    }

    private EventSnapshot publishedEvent(UUID eventId) {
        return events.get(eventId)
                .filter(e -> e.status() == EventStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("sự kiện"));
    }

    public record WaitingRoomStats(Integer admitPerSecond, long waiting, long admitted) {}
}
