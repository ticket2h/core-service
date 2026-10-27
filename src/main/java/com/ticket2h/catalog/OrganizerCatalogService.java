package com.ticket2h.catalog;

import java.math.BigDecimal;
import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticket2h.audit.AuditAction;
import com.ticket2h.audit.AuditEntry;
import com.ticket2h.audit.AuditLog;
import com.ticket2h.catalog.CatalogStore.SeatPosition;
import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;
import com.ticket2h.common.error.ForbiddenException;
import com.ticket2h.common.error.NotFoundException;
import com.ticket2h.common.error.ValidationException;
import com.ticket2h.common.security.AuthUser;
import com.ticket2h.common.security.Role;
import com.ticket2h.identity.UserDirectory;
import com.ticket2h.identity.UserDirectory.UserContact;

/**
 * Thao tác của ban tổ chức. Mọi method nhận {@code organizerId} của người gọi và kiểm tra sự kiện
 * thuộc ban tổ chức đó: BTC A sửa sự kiện của BTC B thì nhận 403. Mỗi thao tác đổi dữ liệu ghi một dòng lịch sử
 * ({@link AuditLog}, ADR-023) trong cùng transaction; người làm lấy từ JWT của request.
 */
@Service
public class OrganizerCatalogService {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi-VN");
    private static final int MAX_SEATS_PER_REQUEST = 5_000;
    /** Cùng giới hạn với ràng buộc events_waiting_room_rate_check (V121). */
    static final int MAX_ADMIT_PER_SECOND = 10_000;
    /** Số dòng mỗi lần gửi INSERT theo lô (JDBC batch) khi nhập ghế từ file. */
    private static final int INSERT_BATCH = 1_000;

    private final CatalogStore store;
    private final UserDirectory users;
    private final AuditLog audit;
    private final TransactionTemplate tx;
    private final Clock clock;

    OrganizerCatalogService(CatalogStore store, UserDirectory users, AuditLog audit, TransactionTemplate tx,
            Clock clock) {
        this.store = store;
        this.users = users;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /** Ban tổ chức của tài khoản ORGANIZER đang gọi, lấy từ claim {@code organizerId} trong JWT. */
    public UUID organizerOf(AuthUser caller) {
        return caller.organizer()
                .orElseThrow(() -> new ForbiddenException("Tài khoản không thuộc ban tổ chức nào"));
    }

    @Transactional
    public VenueRow createVenue(UUID organizerId, String name, String address, String city) {
        UUID id = UUID.randomUUID();
        store.insertVenue(id, organizerId, name.trim(), address.trim(), city.trim(), clock.instant());
        audit.record(AuditEntry.of(AuditAction.VENUE_CREATED, id).organizer(organizerId)
                .detail("name", name.trim()).detail("city", city.trim()));
        return store.findVenue(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<VenueRow> venues(UUID organizerId) {
        return store.venuesOf(organizerId);
    }

    @Transactional
    public EventRow createEvent(UUID organizerId, EventFields fields) {
        VenueRow venue = ownedVenue(organizerId, fields.venueId());
        fields.validate();
        Instant now = clock.instant();
        EventRow event = new EventRow(UUID.randomUUID(), organizerId, venue.id(), fields.title().trim(),
                fields.descriptionOrEmpty(), DEFAULT_ZONE, fields.startsAt(), fields.saleOpensAt(),
                fields.saleClosesAt(), EventStatus.DRAFT, searchText(fields.title(), venue),
                fields.maxTicketsPerUserOrDefault(), now, now, null);
        store.insertEvent(event);
        audit.record(AuditEntry.of(AuditAction.EVENT_CREATED, event.id()).organizer(organizerId).event(event.id())
                .detail("title", event.title()).detail("venueId", event.venueId())
                .detail("startsAt", event.startsAt()).detail("saleOpensAt", event.saleOpensAt())
                .detail("saleClosesAt", event.saleClosesAt()).detail("maxTicketsPerUser", event.maxTicketsPerUser()));
        return event;
    }

    @Transactional
    public EventRow updateEvent(UUID organizerId, UUID eventId, EventFields fields) {
        EventRow current = lockOwnedEvent(organizerId, eventId);
        VenueRow venue = ownedVenue(organizerId, fields.venueId());
        fields.validate();
        EventRow updated = new EventRow(current.id(), current.organizerId(), venue.id(), fields.title().trim(),
                fields.descriptionOrEmpty(), current.timeZone(), fields.startsAt(), fields.saleOpensAt(),
                fields.saleClosesAt(), current.status(), searchText(fields.title(), venue),
                fields.maxTicketsPerUserOrDefault(), current.createdAt(), clock.instant(),
                current.waitingRoomAdmitPerSecond());
        store.updateEvent(updated);
        Map<String, Object> changes = changes(current, updated);
        if (!changes.isEmpty()) {
            audit.record(AuditEntry.of(AuditAction.EVENT_UPDATED, eventId).organizer(organizerId).event(eventId)
                    .detail("changes", changes));
        }
        return updated;
    }

    @Transactional
    public EventRow publish(UUID organizerId, UUID eventId) {
        EventRow event = lockOwnedEvent(organizerId, eventId);
        if (event.status() == EventStatus.PUBLISHED) {
            return event;
        }
        List<TicketTypeRow> types = store.ticketTypesOf(eventId);
        if (types.isEmpty()) {
            throw notPublishable("Sự kiện cần ít nhất một loại vé");
        }
        Map<UUID, Integer> seatCounts = store.seatCounts(eventId);
        for (TicketTypeRow type : types) {
            if (type.kind() == TicketKind.SEATED && seatCounts.getOrDefault(type.id(), 0) == 0) {
                throw notPublishable("Loại vé " + type.name() + " chưa có ghế nào");
            }
        }
        if (!clock.instant().isBefore(event.saleClosesAt())) {
            throw notPublishable("Thời gian bán vé đã kết thúc");
        }
        store.markPublished(eventId, clock.instant());
        audit.record(AuditEntry.of(AuditAction.EVENT_PUBLISHED, eventId).organizer(organizerId).event(eventId)
                .detail("title", event.title()));
        return store.findEvent(eventId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<EventRow> events(UUID organizerId) {
        return store.eventsOf(organizerId);
    }

    @Transactional(readOnly = true)
    public OrganizerEventDetail eventDetail(UUID organizerId, UUID eventId) {
        EventRow event = ownedEvent(organizerId, eventId);
        return new OrganizerEventDetail(event, store.ticketTypesOf(eventId), store.seatCounts(eventId),
                store.staffOf(eventId));
    }

    /** Thêm loại vé. Chỉ khi sự kiện còn DRAFT, để không đổi cấu trúc vé đang bán. */
    @Transactional
    public TicketTypeRow addTicketType(UUID organizerId, UUID eventId, String name, TicketKind kind,
            BigDecimal price, Integer quantity) {
        EventRow event = lockOwnedEvent(organizerId, eventId);
        requireDraft(event);
        if (price.signum() < 0 || price.scale() > 2) {
            throw new ValidationException("Giá vé phải >= 0 và tối đa 2 chữ số thập phân");
        }
        if (kind == TicketKind.GA && (quantity == null || quantity < 1)) {
            throw new ValidationException("Vé GA cần quantity >= 1");
        }
        if (kind == TicketKind.SEATED && quantity != null) {
            throw new ValidationException("Vé có ghế không nhận quantity; số vé bằng số ghế trong sơ đồ");
        }
        String trimmed = name.trim();
        if (store.ticketTypeNameExists(eventId, trimmed)) {
            throw new ValidationException("Tên loại vé đã tồn tại trong sự kiện");
        }
        UUID id = UUID.randomUUID();
        store.insertTicketType(id, eventId, trimmed, kind, price, quantity, clock.instant());
        audit.record(AuditEntry.of(AuditAction.TICKET_TYPE_CREATED, id).organizer(organizerId).event(eventId)
                .detail("name", trimmed).detail("kind", kind.name()).detail("price", price)
                .detail("quantity", quantity));
        return store.ticketTypesOf(eventId).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * Đổi giá loại vé, kể cả khi sự kiện đang bán. Đơn đã tạo giữ giá lúc mua; đơn tạo sau dùng giá mới. Thao tác
     * nhạy cảm nên luôn được ghi lịch sử (giá cũ, giá mới, ai đổi). Giá không đổi thì không ghi gì.
     */
    @Transactional
    public TicketTypeRow changeTicketTypePrice(UUID organizerId, UUID eventId, UUID ticketTypeId, BigDecimal price) {
        lockOwnedEvent(organizerId, eventId);
        if (price.signum() < 0 || price.scale() > 2) {
            throw new ValidationException("Giá vé phải >= 0 và tối đa 2 chữ số thập phân");
        }
        TicketTypeRow type = store.ticketTypesOf(eventId).stream()
                .filter(t -> t.id().equals(ticketTypeId)).findFirst()
                .orElseThrow(() -> new NotFoundException("loại vé"));
        if (type.price().compareTo(price) == 0) {
            return type;
        }
        store.updateTicketTypePrice(eventId, ticketTypeId, price);
        audit.record(AuditEntry.of(AuditAction.TICKET_TYPE_PRICE_CHANGED, ticketTypeId).organizer(organizerId)
                .event(eventId).detail("name", type.name()).detail("from", type.price()).detail("to", price));
        return store.ticketTypesOf(eventId).stream().filter(t -> t.id().equals(ticketTypeId)).findFirst()
                .orElseThrow();
    }

    /**
     * Bật phòng chờ với {@code admitPerSecond} người vào mỗi giây, hoặc tắt ({@code null}), ADR-027. Đổi được cả lúc
     * đang bán: tắt thì ai cũng đặt vé thẳng ngay; bật giữa chừng thì người đang ở trang đặt vé phải vào hàng.
     */
    @Transactional
    public EventRow setWaitingRoom(UUID organizerId, UUID eventId, Integer admitPerSecond) {
        EventRow event = lockOwnedEvent(organizerId, eventId);
        if (admitPerSecond != null && (admitPerSecond < 1 || admitPerSecond > MAX_ADMIT_PER_SECOND)) {
            throw new ValidationException("Số người vào mỗi giây từ 1 tới " + MAX_ADMIT_PER_SECOND);
        }
        if (Objects.equals(event.waitingRoomAdmitPerSecond(), admitPerSecond)) {
            return event;
        }
        store.setWaitingRoom(eventId, admitPerSecond, clock.instant());
        audit.record(AuditEntry.of(AuditAction.WAITING_ROOM_CHANGED, eventId).organizer(organizerId).event(eventId)
                .detail("from", event.waitingRoomAdmitPerSecond()).detail("to", admitPerSecond));
        return store.findEvent(eventId).orElseThrow();
    }

    /** Thêm một khu ghế: mỗi hàng có nhãn và dải số ghế liên tiếp. */
    @Transactional
    public int addSeats(UUID organizerId, UUID eventId, UUID ticketTypeId, String section, List<SeatRow> rows) {
        EventRow event = lockOwnedEvent(organizerId, eventId);
        requireDraft(event);
        TicketTypeRow type = store.ticketTypesOf(eventId).stream()
                .filter(t -> t.id().equals(ticketTypeId)).findFirst()
                .orElseThrow(() -> new ValidationException("Loại vé không thuộc sự kiện"));
        if (type.kind() != TicketKind.SEATED) {
            throw new ValidationException("Chỉ loại vé SEATED mới có sơ đồ ghế");
        }
        List<SeatPosition> positions = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (SeatRow row : rows) {
            if (!labels.add(row.label().trim())) {
                throw new ValidationException("Hàng " + row.label() + " bị lặp");
            }
            if (row.from() < 1 || row.to() < row.from()) {
                throw new ValidationException("Dải số ghế của hàng " + row.label() + " không hợp lệ");
            }
            for (int n = row.from(); n <= row.to(); n++) {
                positions.add(new SeatPosition(section.trim(), row.label().trim(), n));
                if (positions.size() > MAX_SEATS_PER_REQUEST) {
                    throw new ValidationException("Tối đa " + MAX_SEATS_PER_REQUEST + " ghế mỗi lần");
                }
            }
        }
        int created;
        try {
            created = store.insertSeats(eventId, ticketTypeId, positions);
        } catch (DuplicateKeyException e) {
            throw new ValidationException("Có ghế đã tồn tại ở cùng khu, hàng và số");
        }
        audit.record(AuditEntry.of(AuditAction.SEATS_ADDED, eventId).organizer(organizerId).event(eventId)
                .detail("ticketType", type.name()).detail("section", section.trim()).detail("seats", created));
        return created;
    }

    /**
     * Bước hai của nhập ghế từ file ({@link SeatImport}): kiểm tra các dòng với database rồi thêm tất cả, trong một
     * transaction. Khoá sự kiện trước (như {@link #addSeats}), nên hai lần nhập cùng lúc chạy lần lượt và lần sau thấy
     * ghế của lần trước. {@code errors} đã có lỗi của bước một thì vẫn kiểm tra tiếp để báo đủ, nhưng không thêm gì.
     */
    @Transactional
    public SeatImport.ImportResult importSeats(UUID organizerId, UUID eventId, List<SeatImport.SeatLine> lines,
            SeatImport.Errors errors) {
        EventRow event = lockOwnedEvent(organizerId, eventId);
        requireDraft(event);
        List<TicketTypeRow> types = store.ticketTypesOf(eventId);
        Set<SeatPosition> existing = store.seatPositions(eventId);
        Map<SeatPosition, Integer> firstLine = new HashMap<>();
        Map<TicketTypeRow, List<SeatPosition>> toInsert = new LinkedHashMap<>();
        for (SeatImport.SeatLine line : lines) {
            TicketTypeRow type = ticketTypeNamed(types, line.ticketType());
            if (type == null) {
                errors.add(line.line(), "Không có loại vé \"" + line.ticketType() + "\" trong sự kiện");
                continue;
            }
            if (type.kind() != TicketKind.SEATED) {
                errors.add(line.line(), "Loại vé \"" + type.name() + "\" là vé đứng (GA), không có ghế");
                continue;
            }
            SeatPosition position = new SeatPosition(line.section(), line.row(), line.number());
            Integer seen = firstLine.putIfAbsent(position, line.line());
            if (seen != null) {
                errors.add(line.line(), "Trùng ghế với dòng " + seen);
            } else if (existing.contains(position)) {
                errors.add(line.line(), "Ghế đã có trong sơ đồ");
            } else {
                toInsert.computeIfAbsent(type, t -> new ArrayList<>()).add(position);
            }
        }
        if (errors.count() > 0) {
            throw errors.failure();
        }
        Map<String, Integer> byTicketType = new LinkedHashMap<>();
        int created = 0;
        for (Map.Entry<TicketTypeRow, List<SeatPosition>> entry : toInsert.entrySet()) {
            List<SeatPosition> positions = entry.getValue();
            for (int from = 0; from < positions.size(); from += INSERT_BATCH) {
                created += store.insertSeats(eventId, entry.getKey().id(),
                        positions.subList(from, Math.min(positions.size(), from + INSERT_BATCH)));
            }
            byTicketType.put(entry.getKey().name(), positions.size());
        }
        audit.record(AuditEntry.of(AuditAction.SEATS_IMPORTED, eventId).organizer(organizerId).event(eventId)
                .detail("seats", created).detail("byTicketType", byTicketType));
        return new SeatImport.ImportResult(created, byTicketType);
    }

    /** Đúng tên trước; không có thì so không phân biệt hoa thường, nếu chỉ khớp đúng một loại vé. */
    private static TicketTypeRow ticketTypeNamed(List<TicketTypeRow> types, String name) {
        for (TicketTypeRow type : types) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        List<TicketTypeRow> ignoringCase = types.stream().filter(t -> t.name().equalsIgnoreCase(name)).toList();
        return ignoringCase.size() == 1 ? ignoringCase.getFirst() : null;
    }

    /**
     * KHÔNG có {@code @Transactional}: tra email là lời gọi HTTP sang identity-service, không được nằm trong
     * transaction. Kiểm tra quyền trước (BTC khác nhận 403 mà không dò được email nào tồn tại), rồi tra email,
     * rồi mới mở một transaction ngắn cho INSERT và dòng lịch sử (ON CONFLICT DO NOTHING: phân công hai lần vẫn một
     * dòng, và chỉ ghi lịch sử lần đầu).
     */
    public UserContact assignStaff(UUID organizerId, UUID eventId, String staffEmail) {
        ownedEvent(organizerId, eventId);
        UserContact staff = users.findByEmail(staffEmail)
                .filter(u -> u.role() == Role.STAFF)
                .orElseThrow(() -> new ValidationException("Không có tài khoản STAFF với email này"));
        tx.executeWithoutResult(status -> {
            if (store.assignStaff(eventId, staff.id()) == 1) {
                audit.record(AuditEntry.of(AuditAction.STAFF_ASSIGNED, staff.id()).organizer(organizerId)
                        .event(eventId));
            }
        });
        return staff;
    }

    /**
     * Nhân viên được phân công, kèm email và tên lấy từ identity-service (core chỉ lưu id, ADR-013). KHÔNG có
     * {@code @Transactional}: kiểm tra quyền và đọc danh sách id (mỗi câu SELECT tự commit), rồi mới hỏi identity
     * ngoài transaction, một lần cho cả danh sách. Tài khoản đã bị xoá vẫn có trong danh sách với email, tên
     * {@code null}, để BTC gỡ phân công được. Sắp theo tên (thứ tự tiếng Việt), rồi email; tài khoản không còn xếp
     * cuối.
     */
    public List<StaffMember> staff(UUID organizerId, UUID eventId) {
        ownedEvent(organizerId, eventId);
        List<UUID> staffIds = store.staffOf(eventId);
        Map<UUID, UserContact> contacts = staffIds.isEmpty() ? Map.of() : users.findByIds(staffIds);
        List<StaffMember> members = new ArrayList<>(staffIds.size());
        for (UUID id : staffIds) {
            UserContact contact = contacts.get(id);
            members.add(contact == null ? new StaffMember(id, null, null)
                    : new StaffMember(id, contact.email(), contact.fullName()));
        }
        Collator vietnamese = Collator.getInstance(VIETNAMESE);
        members.sort(Comparator.comparing(StaffMember::fullName, Comparator.nullsLast(vietnamese))
                .thenComparing(StaffMember::email, Comparator.nullsLast(Comparator.<String>naturalOrder()))
                .thenComparing(StaffMember::userId));
        return members;
    }

    @Transactional
    public void removeStaff(UUID organizerId, UUID eventId, UUID staffUserId) {
        ownedEvent(organizerId, eventId);
        if (store.removeStaff(eventId, staffUserId) == 1) {
            audit.record(AuditEntry.of(AuditAction.STAFF_REMOVED, staffUserId).organizer(organizerId).event(eventId));
        }
    }

    private EventRow ownedEvent(UUID organizerId, UUID eventId) {
        return requireOwner(organizerId, store.findEvent(eventId).orElseThrow(() -> new NotFoundException("sự kiện")));
    }

    private EventRow lockOwnedEvent(UUID organizerId, UUID eventId) {
        return requireOwner(organizerId, store.lockEvent(eventId).orElseThrow(() -> new NotFoundException("sự kiện")));
    }

    private static EventRow requireOwner(UUID organizerId, EventRow event) {
        if (!event.organizerId().equals(organizerId)) {
            throw new ForbiddenException("Sự kiện thuộc ban tổ chức khác");
        }
        return event;
    }

    private VenueRow ownedVenue(UUID organizerId, UUID venueId) {
        VenueRow venue = store.findVenue(venueId).orElseThrow(() -> new NotFoundException("địa điểm"));
        if (!venue.organizerId().equals(organizerId)) {
            throw new ForbiddenException("Địa điểm thuộc ban tổ chức khác");
        }
        return venue;
    }

    private static void requireDraft(EventRow event) {
        if (event.status() != EventStatus.DRAFT) {
            throw new BusinessException(ErrorCode.EVENT_NOT_EDITABLE,
                    "Sự kiện đã công bố, không thêm loại vé hoặc ghế được nữa");
        }
    }

    /** Các trường đổi giữa hai bản của sự kiện, mỗi trường {@code from}/{@code to}. Mô tả chỉ ghi là có đổi. */
    private static Map<String, Object> changes(EventRow before, EventRow after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        change(changes, "title", before.title(), after.title());
        change(changes, "venueId", before.venueId(), after.venueId());
        change(changes, "startsAt", before.startsAt(), after.startsAt());
        change(changes, "saleOpensAt", before.saleOpensAt(), after.saleOpensAt());
        change(changes, "saleClosesAt", before.saleClosesAt(), after.saleClosesAt());
        change(changes, "maxTicketsPerUser", before.maxTicketsPerUser(), after.maxTicketsPerUser());
        if (!Objects.equals(before.description(), after.description())) {
            changes.put("description", Map.of("changed", true));
        }
        return changes;
    }

    private static void change(Map<String, Object> changes, String field, Object from, Object to) {
        if (!Objects.equals(from, to)) {
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("from", from);
            diff.put("to", to);
            changes.put(field, diff);
        }
    }

    private static BusinessException notPublishable(String detail) {
        return new BusinessException(ErrorCode.EVENT_NOT_PUBLISHABLE, detail);
    }

    private static String searchText(String title, VenueRow venue) {
        return VietnameseText.normalize(title + " " + venue.name() + " " + venue.city());
    }

    public record EventFields(UUID venueId, String title, String description, Instant startsAt,
            Instant saleOpensAt, Instant saleClosesAt, Integer maxTicketsPerUser) {

        void validate() {
            if (!saleOpensAt.isBefore(saleClosesAt)) {
                throw new ValidationException("saleOpensAt phải trước saleClosesAt");
            }
            if (saleClosesAt.isAfter(startsAt)) {
                throw new ValidationException("Phải đóng bán trước hoặc đúng giờ diễn (saleClosesAt <= startsAt)");
            }
            if (maxTicketsPerUser != null && (maxTicketsPerUser < 1 || maxTicketsPerUser > 20)) {
                throw new ValidationException("maxTicketsPerUser phải từ 1 đến 20");
            }
        }

        String descriptionOrEmpty() {
            return description == null ? "" : description;
        }

        int maxTicketsPerUserOrDefault() {
            return maxTicketsPerUser == null ? 4 : maxTicketsPerUser;
        }
    }

    public record SeatRow(String label, int from, int to) {}

    /** {@code email}, {@code fullName} là {@code null} khi identity không còn tài khoản này. */
    public record StaffMember(UUID userId, String email, String fullName) {}

    public record OrganizerEventDetail(EventRow event, List<TicketTypeRow> ticketTypes,
            Map<UUID, Integer> seatCounts, List<UUID> staffUserIds) {}
}
