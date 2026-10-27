package com.ticket2h.common.ratelimit;

import java.time.Duration;

import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;

/** 429, kèm header {@code Retry-After} (giây) và trường {@code retryAfterSeconds} trong ProblemDetail. */
public class RateLimitedException extends BusinessException {

    private final long retryAfterSeconds;

    public RateLimitedException(Duration retryAfter) {
        super(ErrorCode.TOO_MANY_REQUESTS, "Bạn thao tác quá nhanh, hãy thử lại sau ít giây");
        this.retryAfterSeconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        with("retryAfterSeconds", retryAfterSeconds);
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
