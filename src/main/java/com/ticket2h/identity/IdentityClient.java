package com.ticket2h.identity;

import java.net.http.HttpClient;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.common.security.Role;

/**
 * Gọi API nội bộ của identity-service (infra/docs/contracts.md): tra tài khoản theo email, hoặc theo lô id.
 * Có timeout (kết nối 2 giây, đọc 5 giây).
 * KHÔNG gọi bên trong transaction: gọi mạng lúc đang giữ khoá database làm các request khác phải chờ theo.
 * identity không trả lời thì báo 502 {@code IDENTITY_UNAVAILABLE} cho khách, không đổi dữ liệu gì.
 */
@Component
class IdentityClient {

    static final int MAX_BATCH = 1_000;

    private static final Logger log = LoggerFactory.getLogger(IdentityClient.class);

    private final RestClient rest;

    IdentityClient(Ticket2hProperties properties) {
        Ticket2hProperties.IdentityApi config = properties.identityApi();
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(config.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(http);
        requestFactory.setReadTimeout(config.readTimeout());
        this.rest = RestClient.builder()
                .baseUrl(config.baseUrl().toString())
                .requestFactory(requestFactory)
                .defaultHeader("X-Internal-Api-Key", config.apiKey())
                .build();
    }

    /** Email gửi trong body (POST) chứ không trên URL, để không lọt vào log truy cập. Không có tài khoản → rỗng. */
    Optional<IdentityUser> findByEmail(String email) {
        return call(() -> rest.post().uri("/internal/users/lookup")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email))
                .retrieve()
                .body(IdentityUser.class));
    }

    /**
     * {@code POST /internal/users/batch}, tối đa {@value #MAX_BATCH} id. id không có tài khoản thì không có trong kết
     * quả. identity không trả lời → 502 {@code IDENTITY_UNAVAILABLE}.
     */
    List<IdentityUser> findByIds(Collection<UUID> ids) {
        if (ids.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Tối đa " + MAX_BATCH + " id mỗi lần, nhận " + ids.size());
        }
        return call(() -> rest.post().uri("/internal/users/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ids", ids))
                .retrieve()
                .body(Batch.class))
                .map(Batch::users)
                .orElse(List.of());
    }

    /** 404 → rỗng; mã lỗi khác, timeout, không kết nối được → 502 {@code IDENTITY_UNAVAILABLE}. */
    private static <T> Optional<T> call(Supplier<T> request) {
        try {
            return Optional.ofNullable(request.get());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientResponseException e) {
            throw unavailable("trả HTTP " + e.getStatusCode().value());
        } catch (RestClientException e) {
            throw unavailable("không gọi được: " + e.getMessage());
        }
    }

    /** Ghi log lý do (không có email: email là dữ liệu cá nhân), trả lỗi chung cho khách. */
    private static BusinessException unavailable(String reason) {
        log.warn("Tra tài khoản ở identity-service thất bại: {}", reason);
        return new BusinessException(ErrorCode.IDENTITY_UNAVAILABLE,
                "Dịch vụ tài khoản tạm thời không phản hồi, vui lòng thử lại");
    }

    record Batch(List<IdentityUser> users) {}

    /** Body trả về của identity. {@code role} để dạng chuỗi: identity thêm vai trò mới thì core vẫn đọc được. */
    record IdentityUser(UUID id, String email, String fullName, String role, UUID organizerId) {

        Optional<Role> knownRole() {
            try {
                return Optional.of(Role.valueOf(role));
            } catch (IllegalArgumentException | NullPointerException e) {
                return Optional.empty();
            }
        }
    }
}
