package com.ticket2h.inventory;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;

/** 400: ghế không tồn tại, thuộc sự kiện khác, hoặc danh sách ghế bị trùng. */
public class InvalidSeatException extends BusinessException {

    public InvalidSeatException(String detail) {
        super(ErrorCode.VALIDATION_FAILED, detail);
    }
}
