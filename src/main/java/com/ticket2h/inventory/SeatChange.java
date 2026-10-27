package com.ticket2h.inventory;

import java.util.UUID;

/**
 * Một ghế vừa đổi trạng thái (ADR-024): {@code AVAILABLE}, {@code HELD} hoặc {@code SOLD}, kèm số phiên bản của ghế
 * ({@code seats.version}). Trạng thái là giá trị tuyệt đối: áp lại, hoặc nhận lẫn thứ tự, vẫn đúng nếu bên nhận chỉ áp
 * thay đổi có {@code version} lớn hơn cái nó đang có.
 */
public record SeatChange(UUID seatId, String section, String status, long version) {}
