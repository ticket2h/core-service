package com.ticket2h.common.error;

public class ForbiddenException extends BusinessException {

    public ForbiddenException(String detail) {
        super(ErrorCode.FORBIDDEN, detail);
    }
}
