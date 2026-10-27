package com.ticket2h.audit;

import java.util.UUID;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.ticket2h.common.security.AuthUser;

/**
 * Ai làm thao tác. {@code USER}: người dùng gọi API (id, vai trò, ban tổ chức lấy từ JWT). {@code SYSTEM}: job hoặc
 * service khác, có tên ({@code refund-job}, {@code payment-reconciliation}...).
 */
public record AuditActor(Type type, UUID id, String role, UUID organizerId, String name) {

    public enum Type { USER, SYSTEM }

    public static AuditActor user(AuthUser user) {
        return new AuditActor(Type.USER, user.id(), user.role().name(), user.organizerId(), null);
    }

    public static AuditActor system(String name) {
        return new AuditActor(Type.SYSTEM, null, null, null, name);
    }

    /**
     * Người đang gọi API trên luồng hiện tại (Spring Security giữ trong {@code SecurityContextHolder}, như
     * {@code AuditorAware} của Spring Data). Không có người dùng (job, dữ liệu mẫu lúc khởi động): {@code SYSTEM};
     * service gọi {@code /internal/**}: {@code SYSTEM} mang tên service.
     */
    public static AuditActor current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            return user(AuthUser.from(token.getToken()));
        }
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return system(authentication.getName());
        }
        return system("system");
    }
}
