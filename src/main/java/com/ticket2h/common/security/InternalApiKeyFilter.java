package com.ticket2h.common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Xác thực service gọi {@code /internal/**} bằng header {@value #HEADER}. Mỗi service một khoá riêng, nên lộ khoá
 * của service này không mở cửa cho service khác, và log biết service nào gọi. Khoá sai hoặc thiếu thì không làm
 * gì: request đi tiếp như người lạ và bị chặn 401 ở bước phân quyền. Không ghi khoá vào log.
 * <p>
 * KHÔNG khai báo class này là bean: Spring Boot sẽ tự gắn mọi bean {@code Filter} vào MỌI request.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Internal-Api-Key";
    public static final String AUTHORITY = "ROLE_INTERNAL_SERVICE";

    private final Map<String, byte[]> keysByClient;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    public InternalApiKeyFilter(Map<String, String> keysByClient) {
        this.keysByClient = keysByClient.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey, e -> e.getValue().getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null) {
            byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
            for (Map.Entry<String, byte[]> client : keysByClient.entrySet()) {
                // So sánh thời gian hằng: không để kẻ dò khoá đoán từng ký tự qua thời gian phản hồi
                if (MessageDigest.isEqual(client.getValue(), presentedBytes)) {
                    SecurityContext context = contexts.createEmptyContext();
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                            client.getKey(), null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
                    contexts.setContext(context);
                    break;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
