package com.ticket2h.common.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Người đang gọi API, lấy từ JWT do identity-service ký. Khai báo làm tham số controller để Spring tự điền.
 * {@code organizerId}: ban tổ chức của tài khoản ORGANIZER (claim {@code organizerId}), null với vai trò khác.
 */
public record AuthUser(UUID id, Role role, UUID organizerId) {

    public Optional<UUID> organizer() {
        return Optional.ofNullable(organizerId);
    }

    /** Từ JWT đã được Spring Security kiểm tra (claim {@code sub}, {@code role}, {@code organizerId}). */
    public static AuthUser from(Jwt jwt) {
        String organizerId = jwt.getClaimAsString(JwtClaims.ORGANIZER_ID);
        return new AuthUser(UUID.fromString(jwt.getSubject()), Role.valueOf(jwt.getClaimAsString(JwtClaims.ROLE)),
                organizerId == null ? null : UUID.fromString(organizerId));
    }
}
