package com.ticket2h.waitingroom;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;

/** Đặt vé sự kiện có phòng chờ mà không có vé vào cửa hợp lệ (403, ADR-027). */
public class AdmissionRequiredException extends BusinessException {

    public AdmissionRequiredException(String detail) {
        super(ErrorCode.ADMISSION_REQUIRED, detail);
    }
}
