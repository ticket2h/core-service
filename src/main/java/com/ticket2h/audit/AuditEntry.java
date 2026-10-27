package com.ticket2h.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Một dòng lịch sử, chưa có người làm và thời điểm (do {@link AuditLog} điền). {@code organizerId}, {@code eventId}:
 * ban tổ chức và sự kiện liên quan, để lọc. {@code details}: dữ liệu riêng của thao tác, lưu dạng JSON. KHÔNG đưa vào
 * {@code details} email, mật khẩu, token, khoá: lịch sử được giữ lâu và nhiều người xem.
 */
public record AuditEntry(AuditAction action, UUID targetId, UUID organizerId, UUID eventId,
        Map<String, Object> details) {

    public AuditEntry {
        details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static AuditEntry of(AuditAction action, UUID targetId) {
        return new AuditEntry(action, targetId, null, null, Map.of());
    }

    public AuditEntry organizer(UUID newOrganizerId) {
        return new AuditEntry(action, targetId, newOrganizerId, eventId, details);
    }

    public AuditEntry event(UUID newEventId) {
        return new AuditEntry(action, targetId, organizerId, newEventId, details);
    }

    /** Thêm một trường vào {@code details}; giá trị {@code null} được giữ (ghi rõ "không có"). */
    public AuditEntry detail(String key, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(details);
        copy.put(key, value);
        return new AuditEntry(action, targetId, organizerId, eventId, copy);
    }
}
