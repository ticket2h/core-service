package com.ticket2h.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Tạo ProblemDetail (RFC 9457) thống nhất cho cả controller advice lẫn Spring Security. */
public final class ProblemDetails {

    public static ProblemDetail of(ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(code.type());
        problem.setTitle(code.title());
        problem.setProperty("code", code.name());
        return problem;
    }

    public static ProblemDetail of(BusinessException e) {
        ProblemDetail problem = of(e.code(), e.getMessage());
        for (Map.Entry<String, Object> property : e.properties().entrySet()) {
            problem.setProperty(property.getKey(), property.getValue());
        }
        return problem;
    }

    /** Dạng phẳng của ProblemDetail, dùng khi tự ghi JSON ngoài Spring MVC (filter của Spring Security). */
    public static Map<String, Object> toMap(ProblemDetail problem) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", problem.getType().toString());
        map.put("title", problem.getTitle());
        map.put("status", problem.getStatus());
        map.put("detail", problem.getDetail());
        if (problem.getProperties() != null) {
            map.putAll(problem.getProperties());
        }
        return map;
    }

    /** Mã mặc định cho lỗi do Spring MVC ném ra (404 không có route, 405, 400...). */
    public static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.VALIDATION_FAILED;
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            default -> status.is4xxClientError() ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
        };
    }

    private ProblemDetails() {}
}
