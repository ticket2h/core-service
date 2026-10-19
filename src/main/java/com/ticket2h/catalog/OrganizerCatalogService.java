package com.ticket2h.catalog;

import java.math.BigDecimal;
import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * thuộc ban tổ chức đó: BTC A sửa sự kiện của BTC B thì nhận 403.
 */
@Service
public class OrganizerCatalogService {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final Locale VIETNAMESE = Locale.forLanguageTag("vi-VN");
    private static final int MAX_SEATS_PER_REQUEST = 5_000;

    private final CatalogStore store;
    private final UserDirectory users;
    private final Clock clock;

    OrganizerCatalogService(CatalogStore store, UserDirectory users, Clock clock) {
        this.store = store;
        this.users = users;
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
                fields.maxTicketsPerUserOrDefault(), now, now);
        store.insertEvent(event);
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
                fields.maxTicketsPerUserOrDefault(), current.createdAt(), clock.instant());
        store.updateEvent(updated);
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
        return store.ticketTypesOf(eventId).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
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
        try {
            return store.insertSeats(eventId, ticketTypeId, positions);
        } catch (DuplicateKeyException e) {
            throw new ValidationException("Có ghế đã tồn tại ở cùng khu, hàng và số");
        }
    }

    /**
     * KHÔNG có {@code @Transactional}: tra email là lời gọi HTTP sang identity-service, không được nằm trong
     * transaction. Kiểm tra quyền trước (BTC khác nhận 403 mà không dò được email nào tồn tại), rồi tra email,
     * rồi một câu INSERT tự commit (ON CONFLICT DO NOTHING: phân công hai lần vẫn một dòng).
     */
    public UserContact assignStaff(UUID organizerId, UUID eventId, String staffEmail) {
        ownedEvent(organizerId, eventId);
        UserContact staff = users.findByEmail(staffEmail)
                .filter(u -> u.role() == Role.STAFF)
                .orElseThrow(() -> new ValidationException("Không có tài khoản STAFF với email này"));
        store.assignStaff(eventId, staff.id());
        return staff;
    }

    /**
     * Nhân viên được phân công, kèm email và tên lấy từ identity-service (core chỉ lưu id, ADR-013). KHÔNG có
     * {@code @Transactional}: kiểm tra quyền và đọc danh sách id (mỗi câu SELECT tự commit), rồi mới hỏi identity
     * từng người ngoài transaction. Tài khoản đã bị xoá vẫn có trong danh sách với email, tên {@code null}, để BTC
     * gỡ phân công được. Sắp theo tên (thứ tự tiếng Việt), rồi email; tài khoản không còn xếp cuối.
     */
    public List<StaffMember> staff(UUID organizerId, UUID eventId) {
        ownedEvent(organizerId, eventId);
        List<UUID> staffIds = store.staffOf(eventId);
        List<StaffMember> members = new ArrayList<>(staffIds.size());
        for (UUID id : staffIds) {
            members.add(users.findById(id)
                    .map(u -> new StaffMember(id, u.email(), u.fullName()))
                    .orElseGet(() -> new StaffMember(id, null, null)));
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
        store.removeStaff(eventId, staffUserId);
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
