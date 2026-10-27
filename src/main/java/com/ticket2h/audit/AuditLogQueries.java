package com.ticket2h.audit;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ticket2h.audit.AuditLogStore.AuditRow;
import com.ticket2h.common.paging.KeysetCursor;
import com.ticket2h.identity.UserDirectory;
import com.ticket2h.identity.UserDirectory.UserContact;

/**
 * Đọc lịch sử cho trang quản trị, kèm tên và email người làm (hỏi identity một lần cho cả trang). KHÔNG có
 * {@code @Transactional}: đọc trang (một câu SELECT tự commit) rồi mới gọi identity, ngoài transaction.
 */
@Service
public class AuditLogQueries {

    static final int MAX_LIMIT = 100;

    private final AuditLogStore store;
    private final UserDirectory users;

    AuditLogQueries(AuditLogStore store, UserDirectory users) {
        this.store = store;
        this.users = users;
    }

    public AuditPage page(AuditAction action, UUID organizerId, UUID eventId, String cursor, int limit) {
        int safeLimit = Math.min(limit, MAX_LIMIT);
        List<AuditRow> rows = store.page(action, organizerId, eventId, KeysetCursor.decodeOrNull(cursor),
                safeLimit + 1);
        boolean more = rows.size() > safeLimit;
        List<AuditRow> page = more ? rows.subList(0, safeLimit) : rows;
        List<UUID> actorIds = page.stream().map(AuditRow::actorId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, UserContact> actors = actorIds.isEmpty() ? Map.of() : users.findByIds(actorIds);
        String next = more ? new KeysetCursor(page.getLast().occurredAt(), page.getLast().id()).encode() : null;
        return new AuditPage(List.copyOf(page), actors, safeLimit, next);
    }

    public record AuditPage(List<AuditRow> rows, Map<UUID, UserContact> actors, int limit, String nextCursor) {}
}
