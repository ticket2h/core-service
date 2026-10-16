package com.ticket2h.inventory;

import java.util.UUID;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;

/** 409: không đủ vé GA. */
public class SoldOutException extends BusinessException {

    public SoldOutException(UUID ticketTypeId) {
        super(ErrorCode.SOLD_OUT, "Loại vé này không còn đủ số lượng");
        with("ticketTypeId", ticketTypeId);
    }
}
