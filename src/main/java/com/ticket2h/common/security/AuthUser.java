package com.ticket2h.common.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Người đang gọi API, lấy từ JWT do identity-service ký. Khai báo làm tham số controller để Spring tự điền.
 * {@code organizerId}: ban tổ chức của tài khoản ORGANIZER (claim {@code organizerId}), null với vai trò khác.
 */
public record AuthUser(UUID id, Role role, UUID organizerId) {

    public Optional<UUID> organizer() {
        return Optional.ofNullable(organizerId);
    }
}
