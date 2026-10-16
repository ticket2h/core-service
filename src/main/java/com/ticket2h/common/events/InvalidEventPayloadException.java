package com.ticket2h.common.events;

/** Message Kafka hỏng (không phải JSON, thiếu trường). Thử lại vô ích nên error handler đẩy thẳng sang DLT. */
public class InvalidEventPayloadException extends RuntimeException {

    public InvalidEventPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
