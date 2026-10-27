package com.ticket2h.inventory;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lớp lọc đặt trước bước giữ ghế trong database (ADR-028). Database luôn là nơi quyết định ghế của ai: lớp lọc chỉ
 * chặn sớm những request chắc chắn thua, để chúng không phải xếp hàng chờ khoá dòng trong PostgreSQL.
 * Chọn bằng {@code ticket2h.seat-gate}: {@code database} (mặc định, không lọc) hoặc {@code redis}.
 */
public interface SeatGate {

    /** Không lọc: mọi request vào thẳng database. */
    SeatGate DATABASE_ONLY = new SeatGate() {
        @Override
        public <T> T guard(UUID eventId, String owner, List<UUID> seatIds, Supplier<T> holdInDatabase) {
            return holdInDatabase.get();
        }
    };

    /**
     * Chạy {@code holdInDatabase} nếu lớp lọc chưa thấy ghế nào bị người khác giữ. {@code owner}: cùng một request
     * gửi lại (cùng Idempotency-Key) là cùng chủ, không tự chặn chính mình.
     *
     * @throws SeatUnavailableException lớp lọc biết chắc có ghế đã có người
     */
    <T> T guard(UUID eventId, String owner, List<UUID> seatIds, Supplier<T> holdInDatabase);

    /** Sau khi commit thay đổi ghế (giữ, trả, bán): cập nhật lớp lọc. */
    default void committed(UUID eventId, List<SeatChange> changes) {}
}
