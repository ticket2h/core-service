package com.ticket2h.common.ratelimit;

import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.config.Ticket2hProperties.RateLimit.Bucket;

/**
 * Chạy sau Spring Security (đã biết người gọi), trước controller. Mọi request: giới hạn theo IP. Endpoint có
 * {@link RateLimited}: thêm giới hạn theo người dùng. IP là IP thật của trình duyệt: gateway ghi đè
 * {@code X-Forwarded-For}, core tin header này chỉ khi request đến từ mạng nội bộ
 * ({@code server.forward-headers-strategy}).
 */
class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter limiter;
    private final Ticket2hProperties.RateLimit settings;

    RateLimitInterceptor(RateLimiter limiter, Ticket2hProperties.RateLimit settings) {
        this.limiter = limiter;
        this.settings = settings;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!settings.enabled() || !(handler instanceof HandlerMethod method)) {
            return true;
        }
        String ip = request.getRemoteAddr();
        acquire("ip:" + ip, settings.perIp());
        RateLimited limited = method.getMethodAnnotation(RateLimited.class);
        if (limited != null) {
            String subject = userId().map(id -> "user:" + id).orElse("ip:" + ip);
            acquire(limited.value().name().toLowerCase() + ":" + subject, bucketOf(limited.value()));
        }
        return true;
    }

    private void acquire(String key, Bucket bucket) {
        RateLimiter.Decision decision = limiter.tryAcquire(key, bucket);
        if (!decision.allowed()) {
            throw new RateLimitedException(decision.retryAfter());
        }
    }

    private Bucket bucketOf(RateLimited.Policy policy) {
        return switch (policy) {
            case ORDERS -> settings.orders();
            case WAITING_ROOM -> settings.waitingRoom();
        };
    }

    private static Optional<String> userId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? Optional.of(jwt.getName()) : Optional.empty();
    }
}
