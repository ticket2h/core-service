package com.ticket2h.common.events;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Đọc message Kafka: header {@code eventType} và payload JSON. */
@Component
public class EventMessages {

    private final JsonMapper json;

    EventMessages(JsonMapper json) {
        this.json = json;
    }

    /**
     * Loại event của message. Payload phải là JSON object hợp lệ, kể cả khi listener định bỏ qua loại
     * event này: message hỏng phải vào DLT để người kiểm tra, không âm thầm biến mất.
     */
    public String eventType(ConsumerRecord<String, String> record) {
        try {
            if (record.value() == null || !json.readTree(record.value()).isObject()) {
                throw new InvalidEventPayloadException("Payload không phải JSON object", null);
            }
        } catch (JacksonException e) {
            throw new InvalidEventPayloadException("Payload không phải JSON", e);
        }
        Header header = record.headers().lastHeader(Topics.HEADER_EVENT_TYPE);
        if (header == null) {
            throw new InvalidEventPayloadException("Thiếu header " + Topics.HEADER_EVENT_TYPE, null);
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Trường bắt buộc của payload bị thiếu: message hỏng, đẩy thẳng sang DLT thay vì thử lại. */
    public static <T> T required(T value, String field) {
        if (value == null) {
            throw new InvalidEventPayloadException("Payload thiếu trường " + field, null);
        }
        return value;
    }

    public <T> T payload(ConsumerRecord<String, String> record, Class<T> type) {
        try {
            T value = json.readValue(record.value(), type);
            if (value == null) {
                throw new InvalidEventPayloadException("Payload rỗng", null);
            }
            return value;
        } catch (JacksonException e) {
            throw new InvalidEventPayloadException("Payload không đọc được thành " + type.getSimpleName(), e);
        }
    }
}
