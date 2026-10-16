package com.ticket2h.ordering;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Khoá đơn còn hết hạn được. Đơn đang bị khoá (webhook, instance khác) thì bỏ qua. */
    @Query(value = """
            SELECT * FROM orders
            WHERE id = :id AND status = 'PENDING_PAYMENT' AND expires_at < :cutoff
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Order> lockExpirable(@Param("id") UUID id, @Param("cutoff") OffsetDateTime cutoff);

    @Query(value = "SELECT * FROM orders WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Order> lockById(@Param("id") UUID id);

    /** Khoá đơn của đúng user. Đơn của người khác coi như không tồn tại (404). */
    @Query(value = "SELECT * FROM orders WHERE id = :id AND user_id = :userId FOR UPDATE", nativeQuery = true)
    Optional<Order> lockByIdAndUserId(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Không khoá. Danh sách ứng viên cho job dọn đơn hết hạn. */
    @Query(value = """
            SELECT id FROM orders
            WHERE status = 'PENDING_PAYMENT' AND expires_at < :cutoff
            ORDER BY expires_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findExpirableIds(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);

    /** Không khoá. Đơn đã trả tiền từ trước {@code cutoff} mà chưa phát vé. */
    @Query(value = """
            SELECT id FROM orders
            WHERE status = 'PAID' AND paid_at < :cutoff
            ORDER BY paid_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findPaidNotFulfilledIds(@Param("cutoff") OffsetDateTime cutoff, @Param("limit") int limit);

    Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Optional<Order> findByIdAndUserId(UUID id, UUID userId);

    List<Order> findTop50ByUserIdOrderByCreatedAtDesc(UUID userId);
}
