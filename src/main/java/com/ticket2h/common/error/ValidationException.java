package com.ticket2h.common.error;

public class ValidationException extends BusinessException {

    public ValidationException(String detail) {
        super(ErrorCode.VALIDATION_FAILED, detail);
    }
}
