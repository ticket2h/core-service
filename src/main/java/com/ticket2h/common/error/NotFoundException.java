package com.ticket2h.common.error;

/** 404. Dùng cả khi tài nguyên tồn tại nhưng không thuộc người gọi, để không lộ việc nó tồn tại. */
public class NotFoundException extends BusinessException {

    public NotFoundException(String resource) {
        super(ErrorCode.NOT_FOUND, "Không tìm thấy " + resource);
    }
}
