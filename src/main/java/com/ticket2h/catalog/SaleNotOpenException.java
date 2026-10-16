package com.ticket2h.catalog;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;

public class SaleNotOpenException extends BusinessException {

    public SaleNotOpenException() {
        super(ErrorCode.SALE_NOT_OPEN, "Sự kiện chưa mở bán hoặc đã đóng bán");
    }
}
