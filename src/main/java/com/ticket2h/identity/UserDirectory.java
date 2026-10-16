package com.ticket2h.identity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ticket2h.common.security.Role;

/**
 * API công khai của module identity cho các module khác trong core. Tài khoản nay nằm ở identity-service
 * (ADR-013 trong infra/docs/adr/): module này chỉ còn là "cửa" gọi sang đó, các module khác không cần biết.
 * Gọi HTTP, nên KHÔNG gọi bên trong {@code @Transactional}. Thông tin của chính người gọi API (id, vai trò,
 * ban tổ chức) đã có trong JWT ({@code AuthUser}), không cần hỏi identity.
 */
@Service
public class UserDirectory {

    private final IdentityClient identity;

    UserDirectory(IdentityClient identity) {
        this.identity = identity;
    }

    /** Tài khoản theo email. Vai trò lạ (identity thêm vai trò mới mà core chưa biết) coi như không tìm thấy. */
    public Optional<UserContact> findByEmail(String email) {
        return identity.findByEmail(email)
                .flatMap(u -> u.knownRole().map(role -> new UserContact(u.id(), u.email(), u.fullName(), role)));
    }

    public record UserContact(UUID id, String email, String fullName, Role role) {}
}
