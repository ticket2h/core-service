package com.ticket2h.inventory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ticket2h.common.time.Times;

/**
 * Giữ, trả, bán ghế có số. Mọi method chạy trong transaction của bên gọi ({@code MANDATORY})
 * và đổi trạng thái bằng SQL có điều kiện, không load ghế thành entity.
 *
 * <p>Thứ tự khoá: ghế luôn được khoá theo id tăng dần ({@code ORDER BY id FOR UPDATE}). Hai người
 * giữ {A, B} và {B, A} cùng lúc sẽ tranh ghế có id nhỏ hơn trước, nên không deadlock.
 *
 * <p>Mỗi lần đổi trạng thái tăng {@code seats.version} và báo cho sơ đồ ghế trực tiếp ({@link SeatChangeRecorder},
 * phát sau khi commit, ADR-024).
 */
@Service
public class SeatHoldService {

    /** Cột trả về sau mỗi lần đổi trạng thái: đủ để dựng {@link SeatChange}. */
    private static final String CHANGED = "RETURNING id, event_id, section, status, version";

    private final NamedParameterJdbcTemplate jdbc;
    private final SeatChangeRecorder changes;

    public SeatHoldService(NamedParameterJdbcTemplate jdbc, SeatChangeRecorder changes) {
        this.jdbc = jdbc;
        this.changes = changes;
    }

    /**
     * Giữ {@code seatIds} cho đơn {@code orderId} đến {@code heldUntil}. Ghế giữ được khi
     * {@code AVAILABLE}, hoặc {@code HELD} nhưng đã quá {@code held_until} (hết hạn lười).
     *
     * @throws InvalidSeatException ghế trùng, không tồn tại hoặc thuộc sự kiện khác
     * @throws SeatUnavailableException có ghế đang được giữ hoặc đã bán
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void hold(UUID orderId, UUID eventId, List<UUID> seatIds, Instant now, Instant heldUntil) {
        if (seatIds.isEmpty()) {
            return;
        }
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new InvalidSeatException("Danh sách ghế bị trùng");
        }

        List<LockedSeat> locked = jdbc.query("""
                SELECT id, status, held_until
                FROM seats
                WHERE id IN (:seatIds) AND event_id = :eventId
                ORDER BY id
                FOR UPDATE
                """,
                Map.of("seatIds", seatIds, "eventId", eventId),
                (rs, i) -> new LockedSeat(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getObject("held_until", OffsetDateTime.class)));

        if (locked.size() != seatIds.size()) {
            throw new InvalidSeatException("Có ghế không thuộc sự kiện này");
        }

        List<UUID> unavailable = locked.stream()
                .filter(seat -> !seat.claimableAt(now))
                .map(LockedSeat::id)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new SeatUnavailableException(unavailable);
        }

        // Điều kiện lặp lại để phòng thủ: đang giữ khoá nên phải cập nhật đủ số ghế
        int updated = changed("""
                UPDATE seats
                SET status = 'HELD', order_id = :orderId, held_until = :heldUntil, version = version + 1
                WHERE id IN (:seatIds)
                  AND (status = 'AVAILABLE' OR (status = 'HELD' AND held_until < :now))
                """,
                Map.of("orderId", orderId,
                        "heldUntil", Times.utc(heldUntil),
                        "now", Times.utc(now),
                        "seatIds", seatIds));
        if (updated != seatIds.size()) {
            throw new IllegalStateException("Cập nhật " + updated + "/" + seatIds.size() + " ghế dù đang giữ khoá");
        }
    }

    /**
     * Trả các ghế đơn đang giữ. Điều kiện {@code order_id = :orderId} giữ nguyên ghế đã hết hạn
     * và được người khác giữ lại (lúc đó ghế mang order_id của người kia).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int release(UUID orderId) {
        // Khoá theo id tăng dần TRƯỚC khi cập nhật. Một câu UPDATE trần khoá dòng theo thứ tự quét bảng,
        // ngược thứ tự với hold() của người đang giữ lại chính các ghế này → deadlock.
        lockHeldSeats(orderId);
        return changed("""
                UPDATE seats SET status = 'AVAILABLE', order_id = NULL, held_until = NULL, version = version + 1
                WHERE order_id = :orderId AND status = 'HELD'
                """, Map.of("orderId", orderId));
    }

    /** Gia hạn giữ ghế khi khách bắt đầu thanh toán. Trạng thái không đổi nên không tăng version, không báo. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int extend(UUID orderId, Instant heldUntil) {
        lockHeldSeats(orderId);   // cùng lý do với release(): khoá theo id trước
        return jdbc.update("""
                UPDATE seats SET held_until = :heldUntil
                WHERE order_id = :orderId AND status = 'HELD'
                """, Map.of("orderId", orderId, "heldUntil", Times.utc(heldUntil)));
    }

    /**
     * Khoá các ghế đơn còn giữ, theo id tăng dần. Gọi trước khi đếm và chuyển SOLD, để không ai
     * giữ lại được ghế đã hết hạn chen vào giữa lúc đếm và lúc cập nhật.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> lockHeldSeats(UUID orderId) {
        return jdbc.queryForList("""
                SELECT id FROM seats
                WHERE order_id = :orderId AND status = 'HELD'
                ORDER BY id
                FOR UPDATE
                """, Map.of("orderId", orderId), UUID.class);
    }

    /** Chuyển các ghế đơn đang giữ sang SOLD. Bên gọi đã khoá ghế bằng {@link #lockHeldSeats}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int markSold(UUID orderId) {
        return changed("""
                UPDATE seats SET status = 'SOLD', held_until = NULL, version = version + 1
                WHERE order_id = :orderId AND status = 'HELD'
                """, Map.of("orderId", orderId));
    }

    /** Chạy câu UPDATE có {@link #CHANGED}, báo các ghế đã đổi cho sơ đồ ghế trực tiếp; trả số ghế đã đổi. */
    private int changed(String update, Map<String, ?> params) {
        Map<UUID, List<SeatChange>> byEvent = new HashMap<>();
        jdbc.query(update.stripTrailing() + "\n" + CHANGED, params, rs -> {
            byEvent.computeIfAbsent(rs.getObject("event_id", UUID.class), id -> new ArrayList<>())
                    .add(new SeatChange(rs.getObject("id", UUID.class), rs.getString("section"),
                            rs.getString("status"), rs.getLong("version")));
        });
        byEvent.forEach(changes::record);
        return byEvent.values().stream().mapToInt(List::size).sum();
    }

    record LockedSeat(UUID id, String status, OffsetDateTime heldUntil) {
        boolean claimableAt(Instant now) {
            return "AVAILABLE".equals(status)
                    || ("HELD".equals(status) && heldUntil.toInstant().isBefore(now));
        }
    }
}
