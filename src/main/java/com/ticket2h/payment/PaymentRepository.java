package com.ticket2h.payment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface PaymentRepository extends JpaRepository<Payment, UUID> {

    @Query(value = "SELECT * FROM payments WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Payment> lockById(@Param("id") UUID id);

    /**
     * Chỉ lấy order_id, KHÔNG load entity. Nếu load Payment ở đây rồi mới khoá, lockById sau đó trả
     * về chính entity đã có trong persistence context với trạng thái đọc TRƯỚC khi khoá (Hibernate
     * không ghi đè entity đang quản lý bằng kết quả query mới).
     */
    @Query("select p.orderId from Payment p where p.id = :id")
    Optional<UUID> findOrderIdById(@Param("id") UUID id);

    long countByOrderId(UUID orderId);

    List<Payment> findByOrderIdOrderByCreatedAtDesc(UUID orderId);
}
