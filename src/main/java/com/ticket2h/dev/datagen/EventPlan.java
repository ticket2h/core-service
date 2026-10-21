package com.ticket2h.dev.datagen;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

import com.ticket2h.catalog.TicketKind;
import com.ticket2h.ordering.OrderStatus;
import com.ticket2h.ordering.SalesChannel;
import com.ticket2h.ticketing.CheckinResult;

/**
 * Kế hoạch dữ liệu của MỘT sự kiện, tính hoàn toàn từ (seed, số thứ tự sự kiện): đơn thứ k luôn ra cùng người mua,
 * cùng loại vé, cùng giờ, dù hỏi bao nhiêu lần. Nhờ vậy công cụ sinh đi qua danh sách đơn nhiều lượt (lượt ghi
 * {@code orders}, lượt ghi {@code seats}, lượt ghi {@code tickets}...) mà không phải giữ 200.000 đơn trong bộ nhớ,
 * và khoá ngoại luôn đúng: bảng được ghi trước (orders) đã có đủ dòng mà bảng sau (order_items) trỏ tới.
 * <p>
 * Ghế được bán lần lượt: đơn nào mua ghế thì lấy các ghế kế tiếp chưa bán của loại vé đó. Lượt nào cần biết ghế
 * cũng đi đơn theo đúng thứ tự k = 0, 1, 2... nên ra cùng kết quả.
 */
final class EventPlan {

    static final int TAG_EVENT = 0x11;
    static final int TAG_TICKET_TYPE = 0x12;
    static final int TAG_SEAT = 0x13;
    static final int TAG_ORDER = 0x14;
    static final int TAG_ITEM = 0x15;
    static final int TAG_PAYMENT = 0x16;
    static final int TAG_TICKET = 0x17;
    static final int TAG_VENUE = 0x18;

    /** Bước nhảy khi chọn người mua: nguyên tố, nên k khác nhau ra người mua khác nhau trong một sự kiện. */
    private static final long BUYER_STRIDE = 1_000_003L;
    private static final int STAFF_PER_EVENT = 3;

    private static final String[] PREFIXES = {"Đêm nhạc", "Liveshow", "Lễ hội", "Concert", "Show diễn", "Đại nhạc hội"};
    private static final String[] THEMES = {"Acoustic", "Indie", "Rock", "Jazz", "Bolero", "EDM", "Hip-hop",
            "Nhạc Trịnh", "Giao hưởng", "Kịch nói", "Hài độc thoại", "Ballad", "K-pop", "Nhạc phim", "Thiếu nhi"};

    final int index;
    final long seed;
    final UUID id;
    final UUID organizerId;
    final UUID venueId;
    final String title;
    final Instant startsAt;
    final Instant saleOpensAt;
    final Instant saleClosesAt;
    final Instant now;
    final int orders;
    final int customers;
    final List<TypePlan> types;
    final List<UUID> staff;

    private final long buyerOffset;

    EventPlan(int index, long seed, UUID organizerId, UUID venueId, String city, Instant startsAt,
            Instant saleOpensAt, Instant now, int orders, int customers, int staffCount, boolean big) {
        this.index = index;
        this.seed = seed;
        this.id = DatagenIds.of(TAG_EVENT, index, 0);
        this.organizerId = organizerId;
        this.venueId = venueId;
        this.startsAt = startsAt;
        this.saleOpensAt = saleOpensAt;
        this.saleClosesAt = startsAt.minus(Duration.ofHours(1));
        this.now = now;
        this.orders = orders;
        this.customers = customers;
        SplittableRandom r = rng(0, 0, 0);
        this.title = big ? "Đại nhạc hội Ticket2h 2026"
                : PREFIXES[r.nextInt(PREFIXES.length)] + " " + THEMES[r.nextInt(THEMES.length)] + " " + city + " #"
                        + index;
        this.buyerOffset = r.nextLong(customers);
        this.types = big
                ? List.of(new TypePlan(0, "VIP", TicketKind.SEATED, new BigDecimal("3500000")),
                        new TypePlan(1, "Ghế hạng A", TicketKind.SEATED, new BigDecimal("2000000")),
                        new TypePlan(2, "Vé đứng", TicketKind.GA, new BigDecimal("1200000")),
                        new TypePlan(3, "Vé đứng mở bán sớm", TicketKind.GA, new BigDecimal("950000")))
                : List.of(new TypePlan(0, "Ghế ngồi", TicketKind.SEATED, price(r, 800_000, 2_500_000)),
                        new TypePlan(1, "Vé đứng", TicketKind.GA, price(r, 300_000, 1_200_000)));
        List<UUID> staffIds = new ArrayList<>(STAFF_PER_EVENT);
        for (int s = 0; s < STAFF_PER_EVENT; s++) {
            staffIds.add(DatagenIds.staff(1 + Math.floorMod((long) index * STAFF_PER_EVENT + s, staffCount)));
        }
        this.staff = List.copyOf(staffIds);
    }

    boolean isPast() {
        return startsAt.isBefore(now);
    }

    /**
     * Lượt lên kế hoạch: đếm vé bán theo loại để biết cần bao nhiêu ghế, bao nhiêu vé GA. Trả số vé sẽ được quét vào
     * cổng (để công cụ sinh chia đều số lượt quét còn lại).
     */
    long plan() {
        long used = 0;
        for (int k = 0; k < orders; k++) {
            OrderSpec order = order(k);
            if (order.status() == OrderStatus.FULFILLED) {
                types.get(order.typeIndex()).sold += order.quantity();
                for (int j = 0; j < order.quantity(); j++) {
                    if (ticket(order, j).checkedInAt() != null) {
                        used++;
                    }
                }
            }
        }
        for (TypePlan type : types) {
            // Ghế và vé GA còn trống: khoảng 10% chưa bán, sự kiện đang mở bán vẫn mua thử được
            type.capacity = type.sold + Math.max(20, type.sold / 10);
        }
        return used;
    }

    /** Đơn thứ {@code k}. Chỉ phụ thuộc (seed, sự kiện, k). */
    OrderSpec order(int k) {
        SplittableRandom r = rng(1, k, 0);
        UUID buyer = DatagenIds.customer(1 + Math.floorMod(buyerOffset + k * BUYER_STRIDE, (long) customers));
        double s = r.nextDouble();
        OrderStatus status = s < 0.90 ? OrderStatus.FULFILLED : s < 0.96 ? OrderStatus.EXPIRED : OrderStatus.CANCELLED;
        SalesChannel channel = r.nextDouble() < 0.65 ? SalesChannel.WEB : SalesChannel.MOBILE_APP;
        double q = r.nextDouble();
        int quantity = q < 0.4 ? 1 : q < 0.7 ? 2 : q < 0.85 ? 3 : 4;
        // Đơn hết hạn hay bị huỷ không còn giữ ghế; cho chúng mua vé GA để khỏi phải theo dõi ghế đã nhả
        boolean seated = status == OrderStatus.FULFILLED && r.nextDouble() < 0.3;
        int typeIndex = pickType(r, seated ? TicketKind.SEATED : TicketKind.GA);
        Instant created = createdAt(r);
        Instant paid = status == OrderStatus.FULFILLED ? created.plusSeconds(30 + r.nextInt(450)) : null;
        Instant fulfilled = paid == null ? null : paid.plusSeconds(1 + r.nextInt(4));
        return new OrderSpec(k, DatagenIds.of(TAG_ORDER, index, k), buyer, status, channel, typeIndex, quantity,
                created, paid, fulfilled);
    }

    /** Vé thứ {@code j} của đơn (đã FULFILLED): sự kiện đã diễn thì 93% vé đã vào cổng. */
    TicketSpec ticket(OrderSpec order, int j) {
        UUID ticketId = DatagenIds.of(TAG_TICKET, index, (long) order.k() * 8 + j);
        if (!isPast()) {
            return new TicketSpec(ticketId, null, null, null);
        }
        SplittableRandom r = rng(2, order.k(), j);
        if (r.nextDouble() >= 0.93) {
            return new TicketSpec(ticketId, null, null, null);
        }
        Instant checkedIn = startsAt.minus(Duration.ofHours(2)).plusSeconds(r.nextInt(3 * 3600));
        return new TicketSpec(ticketId, checkedIn, "Cổng " + (1 + r.nextInt(6)), staff.get(r.nextInt(staff.size())));
    }

    /**
     * Các lượt quét THÊM của một vé đã vào cổng (quét lại, QR cũ, QR hỏng), sau lượt quét hợp lệ. {@code extraRate}:
     * số lượt thêm trung bình mỗi vé, để tổng số dòng checkin_logs gần đúng con số đặt ra.
     */
    List<ScanSpec> extraScans(OrderSpec order, int j, TicketSpec ticket, double extraRate) {
        SplittableRandom r = rng(3, order.k(), j);
        int n = (int) extraRate + (r.nextDouble() < extraRate - (int) extraRate ? 1 : 0);
        List<ScanSpec> scans = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double kind = r.nextDouble();
            CheckinResult result = kind < 0.8 ? CheckinResult.ALREADY_USED
                    : kind < 0.88 ? CheckinResult.TOKEN_REVOKED : CheckinResult.INVALID_TOKEN;
            boolean known = result != CheckinResult.INVALID_TOKEN;   // QR hỏng thì không biết vé, không biết sự kiện
            scans.add(new ScanSpec(known ? ticket.id() : null, known ? id : null, ticket.staffId(), ticket.gate(),
                    result, ticket.checkedInAt().plusSeconds(1 + r.nextInt(1800))));
        }
        return scans;
    }

    /** Một nửa số đơn đổ vào 2 giờ đầu mở bán (giờ mở bán thật luôn đông nhất), phần còn lại rải đều tới lúc đóng. */
    private Instant createdAt(SplittableRandom r) {
        Instant end = saleClosesAt.isBefore(now) ? saleClosesAt : now;
        long window = Math.max(1, Duration.between(saleOpensAt, end).getSeconds() - 600);
        long offset = r.nextDouble() < 0.5 ? r.nextLong(Math.min(window, 2 * 3600)) : r.nextLong(window);
        return saleOpensAt.plusSeconds(offset);
    }

    private int pickType(SplittableRandom r, TicketKind kind) {
        List<TypePlan> matching = types.stream().filter(t -> t.kind == kind).toList();
        return matching.get(r.nextInt(matching.size())).index;
    }

    private static BigDecimal price(SplittableRandom r, int min, int max) {
        int steps = (max - min) / 50_000;
        return BigDecimal.valueOf(min + 50_000L * r.nextInt(steps + 1));
    }

    /**
     * Mỗi (mục đích, k, j) một dãy số ngẫu nhiên riêng, để thêm hay bớt một lần rút ở chỗ này không làm lệch chỗ
     * khác.
     */
    private SplittableRandom rng(int purpose, long k, long j) {
        long h = seed;
        h = mix(h ^ index);
        h = mix(h ^ purpose);
        h = mix(h ^ k);
        h = mix(h ^ j);
        return new SplittableRandom(h);
    }

    /** Hàm trộn của SplitMix64. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    static final class TypePlan {
        final int index;
        final String name;
        final TicketKind kind;
        final BigDecimal price;
        int sold;
        int capacity;

        TypePlan(int index, String name, TicketKind kind, BigDecimal price) {
            this.index = index;
            this.name = name;
            this.kind = kind;
            this.price = price;
        }
    }

    /** {@code paidAt}, {@code fulfilledAt} null khi đơn hết hạn hoặc bị huỷ. */
    record OrderSpec(int k, UUID id, UUID buyer, OrderStatus status, SalesChannel channel, int typeIndex, int quantity,
            Instant createdAt, Instant paidAt, Instant fulfilledAt) {}

    /** {@code checkedInAt} null: vé chưa vào cổng. */
    record TicketSpec(UUID id, Instant checkedInAt, String gate, UUID staffId) {}

    record ScanSpec(UUID ticketId, UUID eventId, UUID staffId, String gate, CheckinResult result, Instant scannedAt) {}
}
