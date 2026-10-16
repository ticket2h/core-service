package com.ticket2h.inventory;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hạn mức vé mỗi user cho một sự kiện. Kiểm tra và cộng trong một câu lệnh, nên hai tab gửi song
 * song không cùng "đọc thấy còn chỗ" được: {@code ON CONFLICT DO UPDATE} khoá dòng, các request
 * của cùng user chạy lần lượt.
 *
 * <p>Hạn mức tính trên vé đã giữ hoặc đã mua. Chuyển nhượng vé không đổi hạn mức của ai (ADR-004).
 */
@Service
public class PurchaseQuotas {

    private final NamedParameterJdbcTemplate jdbc;

    public PurchaseQuotas(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws PurchaseLimitExceededException tổng vé sau khi cộng vượt {@code limit} */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID userId, UUID eventId, int quantity, int limit) {
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity phải >= 1");
        }
        // Chưa có dòng thì câu dưới INSERT thẳng, không qua điều kiện WHERE, nên phải chặn trước
        if (quantity > limit) {
            throw new PurchaseLimitExceededException(limit);
        }
        int updated = jdbc.update("""
                INSERT INTO purchase_quotas (user_id, event_id, reserved)
                VALUES (:userId, :eventId, :quantity)
                ON CONFLICT (user_id, event_id)
                DO UPDATE SET reserved = purchase_quotas.reserved + EXCLUDED.reserved
                WHERE purchase_quotas.reserved + EXCLUDED.reserved <= :limit
                """, Map.of("userId", userId, "eventId", eventId, "quantity", quantity, "limit", limit));
        if (updated == 0) {
            throw new PurchaseLimitExceededException(limit);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID userId, UUID eventId, int quantity) {
        int updated = jdbc.update("""
                UPDATE purchase_quotas SET reserved = reserved - :quantity
                WHERE user_id = :userId AND event_id = :eventId
                """, Map.of("quantity", quantity, "userId", userId, "eventId", eventId));
        if (updated != 1) {
            throw new IllegalStateException("Không có hạn mức để trả cho user " + userId);
        }
    }
}
