package com.ticket2h.common.ratelimit;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.ticket2h.common.config.Ticket2hProperties;

/**
 * Bỏ qua: webhook (cổng thanh toán gọi từ vài IP, đã có chữ ký), API nội bộ giữa các service, actuator.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class RateLimitConfig implements WebMvcConfigurer {

    private final RateLimiter limiter;
    private final Ticket2hProperties properties;

    RateLimitConfig(RateLimiter limiter, Ticket2hProperties properties) {
        this.limiter = limiter;
        this.properties = properties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(limiter, properties.rateLimit()))
                .excludePathPatterns("/webhooks/**", "/internal/**", "/actuator/**");
    }
}
