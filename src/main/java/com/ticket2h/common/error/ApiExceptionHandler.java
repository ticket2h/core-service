package com.ticket2h.common.error;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.ticket2h.common.ratelimit.RateLimitedException;

/** Mọi lỗi API trả theo RFC 9457 (ProblemDetail) kèm trường {@code code}. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ProblemDetail> handleBusiness(BusinessException e) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.code().status());
        if (e instanceof RateLimitedException limited) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()));
        }
        return response.body(ProblemDetails.of(e));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        // Ví dụ /orders/abc: id không phải UUID thì coi như không tồn tại
        if (e.getRequiredType() == java.util.UUID.class) {
            return handleBusiness(new NotFoundException("tài nguyên"));
        }
        return handleBusiness(new ValidationException("Tham số " + e.getName() + " không hợp lệ"));
    }

    /** Từ {@code @PreAuthorize}: đã đăng nhập nhưng sai vai trò. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException e) {
        return handleBusiness(new ForbiddenException("Vai trò của bạn không được phép thực hiện thao tác này"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception e) {
        log.error("Lỗi không xử lý được", e);
        ProblemDetail problem = ProblemDetails.of(ErrorCode.INTERNAL_ERROR, "Đã có lỗi xảy ra, vui lòng thử lại");
        return ResponseEntity.internalServerError().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::toError)
                .toList();
        ProblemDetail problem = ProblemDetails.of(ErrorCode.VALIDATION_FAILED, "Dữ liệu gửi lên không hợp lệ");
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException e, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetails.of(ErrorCode.VALIDATION_FAILED, "Tham số không hợp lệ");
        return ResponseEntity.badRequest().body(problem);
    }

    /** Lỗi do Spring MVC ném (JSON hỏng, 404 không có route, 405...): thêm {@code code} cho thống nhất. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception e, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(e, body, headers, status, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
            ErrorCode code = ProblemDetails.codeFor(status);
            problem.setType(code.type());
            problem.setProperty("code", code.name());
        }
        return response;
    }

    private static Map<String, String> toError(FieldError error) {
        return Map.of("field", error.getField(),
                "message", error.getDefaultMessage() == null ? "không hợp lệ" : error.getDefaultMessage());
    }
}
