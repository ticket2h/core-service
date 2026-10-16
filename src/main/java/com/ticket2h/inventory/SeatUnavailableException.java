package com.ticket2h.inventory;

import java.util.List;
import java.util.UUID;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;

/** 409: có ghế đã được người khác giữ hoặc đã bán. */
public class SeatUnavailableException extends BusinessException {

    private final List<UUID> unavailableSeatIds;

    public SeatUnavailableException(List<UUID> unavailableSeatIds) {
        super(ErrorCode.SEAT_UNAVAILABLE, "Có ghế đã được người khác giữ");
        this.unavailableSeatIds = List.copyOf(unavailableSeatIds);
        with("unavailableSeatIds", this.unavailableSeatIds);
    }

    public List<UUID> unavailableSeatIds() {
        return unavailableSeatIds;
    }
}
