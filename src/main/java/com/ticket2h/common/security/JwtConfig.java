package com.ticket2h.common.security;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.ticket2h.common.config.Ticket2hProperties;

/**
 * Chỉ KIỂM TRA JWT, không phát: token do identity-service ký RS256 bằng private key mà chỉ identity giữ. core lấy
 * public key từ JWKS của identity ({@code ticket2h.jwt.jwk-set-uri}) lúc gặp token đầu tiên rồi giữ trong bộ nhớ;
 * gặp {@code kid} lạ (identity đổi khoá) thì tự tải lại. Chỉ chấp nhận RS256: token ký thuật toán khác (kể cả HS256
 * kiểu cũ) bị từ chối. Xem ADR-013 trong infra/docs/adr/.
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfig {

    @Bean
    JwtDecoder jwtDecoder(Ticket2hProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwt().jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        // Kiểm tra hạn token theo cùng Clock với phần còn lại của ứng dụng (test dùng MutableClock)
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(properties.jwt().issuer())));
        return decoder;
    }
}
