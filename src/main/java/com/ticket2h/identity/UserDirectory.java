package com.ticket2h.identity;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        return identity.findByEmail(email).flatMap(UserDirectory::toContact);
    }

    /**
     * Nhiều tài khoản theo id (tên nhân viên soát vé, khách trong file xuất; core chỉ lưu id), chia thành lô 1.000 id
     * mỗi lần gọi identity. id không có tài khoản (đã xoá) hoặc vai trò
     * lạ thì không có trong kết quả. identity không trả lời → {@code IDENTITY_UNAVAILABLE}.
     */
    public Map<UUID, UserContact> findByIds(Collection<UUID> ids) {
        List<UUID> distinct = List.copyOf(new LinkedHashSet<>(ids));
        Map<UUID, UserContact> contacts = new HashMap<>(distinct.size() * 2);
        for (int from = 0; from < distinct.size(); from += IdentityClient.MAX_BATCH) {
            List<UUID> chunk = distinct.subList(from, Math.min(distinct.size(), from + IdentityClient.MAX_BATCH));
            for (IdentityClient.IdentityUser user : identity.findByIds(chunk)) {
                toContact(user).ifPresent(contact -> contacts.put(contact.id(), contact));
            }
        }
        return contacts;
    }

    private static Optional<UserContact> toContact(IdentityClient.IdentityUser u) {
        return u.knownRole().map(role -> new UserContact(u.id(), u.email(), u.fullName(), role));
    }

    public record UserContact(UUID id, String email, String fullName, Role role) {}
}
