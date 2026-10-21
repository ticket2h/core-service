package com.ticket2h.common.paging;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

import com.ticket2h.common.error.ValidationException;

/**
 * Vị trí trong một danh sách phân trang keyset (ADR-018), sắp theo thời gian rồi tới id: thời gian và id của dòng
 * cuối trang trước. Gửi cho client dạng chuỗi mờ (base64url); client chỉ gửi lại nguyên văn. Không phải bí mật: sửa
 * tay chỉ nhảy tới chỗ khác trong danh sách mà người đó vốn đã được xem (quyền vẫn kiểm tra theo sự kiện).
 */
public record KeysetCursor(Instant at, UUID id) {

    public String encode() {
        String raw = at + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code null} hoặc rỗng: trang đầu. Chuỗi hỏng: 400 VALIDATION_FAILED. */
    public static KeysetCursor decodeOrNull(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.indexOf('|');
            if (separator < 0) {
                throw new IllegalArgumentException("thiếu dấu |");
            }
            return new KeysetCursor(Instant.parse(raw.substring(0, separator)),
                    UUID.fromString(raw.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new ValidationException("cursor không hợp lệ");
        }
    }
}
