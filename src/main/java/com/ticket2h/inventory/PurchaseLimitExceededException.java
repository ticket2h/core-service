package com.ticket2h.inventory;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;

/** 409: vượt số vé tối đa mỗi user cho một sự kiện. */
public class PurchaseLimitExceededException extends BusinessException {

    public PurchaseLimitExceededException(int limit) {
        super(ErrorCode.PURCHASE_LIMIT_EXCEEDED, "Mỗi người mua tối đa " + limit + " vé cho sự kiện này");
        with("limit", limit);
    }
}
