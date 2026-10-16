package com.ticket2h.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gốc của mọi exception nghiệp vụ. Là unchecked exception để Spring rollback transaction
 * (mặc định Spring không rollback với checked exception).
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties = new LinkedHashMap<>();

    public BusinessException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    /** Trường bổ sung trong ProblemDetail, ví dụ {@code unavailableSeatIds}. */
    public Map<String, Object> properties() {
        return properties;
    }

    protected BusinessException with(String name, Object value) {
        properties.put(name, value);
        return this;
    }
}
