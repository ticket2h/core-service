package com.ticket2h.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Min;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ticket2h.audit.AuditLogQueries.AuditPage;
import com.ticket2h.audit.AuditLogStore.AuditRow;
import com.ticket2h.identity.UserDirectory.UserContact;

/** Lịch sử thao tác cho ADMIN (SecurityConfig chặn theo /admin/**). */
@RestController
@RequestMapping("/admin/audit-logs")
class AuditLogController {

    private final AuditLogQueries queries;

    AuditLogController(AuditLogQueries queries) {
        this.queries = queries;
    }

    /**
     * Mới nhất trước, phân trang keyset: trang sau gửi lại {@code nextCursor} trong {@code cursor}. Lọc theo
     * {@code action}, {@code organizerId}, {@code eventId} (tuỳ chọn). {@code limit} tối đa 100.
     */
    @GetMapping
    AuditLogPage list(@RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) UUID organizerId, @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") @Min(1) int limit) {
        AuditPage page = queries.page(action, organizerId, eventId, cursor, limit);
        return new AuditLogPage(page.rows().stream().map(row -> AuditLogItem.of(row, page.actors())).toList(),
                page.limit(), page.nextCursor());
    }

    record AuditLogPage(List<AuditLogItem> items, int limit, String nextCursor) {}

    /**
     * {@code actor.type}: USER hoặc SYSTEM. Với USER: {@code id}, {@code role}, và {@code email}, {@code fullName} tra
     * từ identity (null nếu tài khoản không còn). Với SYSTEM: {@code name} (refund-job, payment-reconciliation...).
     */
    record AuditLogItem(UUID id, Instant occurredAt, Actor actor, String action, String targetType, UUID targetId,
            UUID organizerId, UUID eventId, Map<String, Object> details) {

        static AuditLogItem of(AuditRow row, Map<UUID, UserContact> actors) {
            UserContact contact = row.actorId() == null ? null : actors.get(row.actorId());
            return new AuditLogItem(row.id(), row.occurredAt(),
                    new Actor(row.actorType().name(), row.actorId(), row.actorRole(), row.actorName(),
                            contact == null ? null : contact.email(), contact == null ? null : contact.fullName()),
                    row.action(), row.targetType(), row.targetId(), row.organizerId(), row.eventId(), row.details());
        }
    }

    record Actor(String type, UUID id, String role, String name, String email, String fullName) {}
}
