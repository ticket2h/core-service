package com.ticket2h.common.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;

import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.common.error.ProblemDetails;

import tools.jackson.databind.json.JsonMapper;

/**
 * Token hỏng hay hết hạn thì Spring Security trả 401. Nhưng nếu core KHÔNG KIỂM TRA ĐƯỢC token vì chưa lấy được
 * public key (JWKS của identity-service không trả lời), Spring ném {@link AuthenticationServiceException} ra khỏi
 * chuỗi filter và khách nhận 500 dạng mặc định. Filter này đứng trước bước kiểm tra token, đổi lỗi đó thành 502
 * {@code IDENTITY_UNAVAILABLE} dạng ProblemDetail như mọi lỗi khác: token của khách có thể vẫn hợp lệ, không nên
 * bảo họ đăng nhập lại. Chỉ xảy ra khi core chưa từng lấy được JWKS (ví dụ vừa khởi động); lấy được một lần rồi thì
 * core giữ key trong bộ nhớ. KHÔNG khai báo là bean (Spring Boot sẽ gắn nó vào mọi request).
 */
class TokenVerificationUnavailableFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenVerificationUnavailableFilter.class);

    private final JsonMapper json;

    TokenVerificationUnavailableFilter(JsonMapper json) {
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (AuthenticationServiceException e) {
            if (response.isCommitted()) {
                throw e;
            }
            // Thông báo lỗi chỉ có URL của JWKS và lý do, không có token
            log.warn("Không kiểm tra được JWT (JWKS của identity-service?): {}", e.getMessage());
            ProblemDetail problem = ProblemDetails.of(ErrorCode.IDENTITY_UNAVAILABLE,
                    "Tạm thời không kiểm tra được đăng nhập, vui lòng thử lại sau");
            response.setStatus(problem.getStatus());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            json.writeValue(response.getOutputStream(), ProblemDetails.toMap(problem));
        }
    }
}
