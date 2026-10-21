package com.ticket2h.dev.datagen;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ticket2h.catalog.TicketKind;
import com.ticket2h.catalog.VietnameseText;
import com.ticket2h.dev.datagen.EventPlan.OrderSpec;
import com.ticket2h.dev.datagen.EventPlan.ScanSpec;
import com.ticket2h.dev.datagen.EventPlan.TicketSpec;
import com.ticket2h.dev.datagen.EventPlan.TypePlan;
import com.ticket2h.ordering.OrderStatus;
import com.ticket2h.payment.Payment;
import com.ticket2h.reporting.SalesRollup;
import com.ticket2h.ticketing.CheckinLogPartitions;

/**
 * Công cụ sinh dữ liệu lớn cho M4 (profile {@code datagen}, ADR-021 ở infra/docs/adr): sự kiện trong 2 năm, đơn,
 * thanh toán, vé, lượt quét, đủ để thấy truy vấn nào chậm. Chạy xong thì tắt app. Tài khoản do identity-service sinh
 * (cùng công thức id, {@link DatagenIds}); core không gọi identity.
 * <p>
 * Mỗi sự kiện ghi trong MỘT transaction, theo thứ tự khoá ngoại (orders trước seats, seats trước order_items...), bằng
 * {@code COPY}. Các sự kiện độc lập nhau nên chạy song song ({@code threads} kết nối). Dữ liệu tính lại được từ bảng
 * gốc (bảng tổng hợp doanh thu, hạn mức mua) được tính bằng SQL sau cùng, nên luôn khớp với bảng gốc.
 * Database đã có dữ liệu sinh thì dừng, không sinh chồng lên.
 */
@Component
@Profile("datagen")
@Order(2)   // sau dữ liệu mẫu (profile dev): sự kiện lớn thuộc ban tổ chức mẫu Sky
class CoreDataGenerator implements ApplicationRunner {

    /** Ban tổ chức mẫu "Sky" của profile dev (DevAccounts của identity): sự kiện lớn thuộc về Sky để xem trên web. */
    static final java.util.UUID ORGANIZER_SKY = java.util.UUID.fromString("0a000000-0000-4000-8000-000000000001");

    private static final Logger log = LoggerFactory.getLogger(CoreDataGenerator.class);
    private static final String[] CITIES = {"Hà Nội", "TP. Hồ Chí Minh", "Đà Nẵng", "Huế", "Hải Phòng", "Cần Thơ",
            "Nha Trang", "Đà Lạt"};
    private static final Duration HISTORY = Duration.ofDays(730);
    private static final Duration AHEAD = Duration.ofDays(90);

    private final DatagenProperties props;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final SalesRollup salesRollup;
    private final CheckinLogPartitions partitions;
    private final Clock clock;
    private final ConfigurableApplicationContext context;

    CoreDataGenerator(DatagenProperties props, DataSource dataSource, JdbcTemplate jdbc, SalesRollup salesRollup,
            CheckinLogPartitions partitions, Clock clock, ConfigurableApplicationContext context) {
        this.props = props;
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.salesRollup = salesRollup;
        this.partitions = partitions;
        this.clock = clock;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        int exitCode = 0;
        try {
            generate();
        } catch (Exception e) {
            log.error("Sinh dữ liệu thất bại", e);
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }

    void generate() throws Exception {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        List<EventPlan> plans = plans(now);
        if (alreadyGenerated(plans.getFirst())) {
            log.warn("Database đã có dữ liệu sinh (sự kiện {}), dừng. Muốn sinh lại thì xoá volume database "
                    + "(docker compose down -v) rồi chạy lại.", plans.getFirst().id);
            return;
        }
        long started = System.nanoTime();
        log.info("Lên kế hoạch {} sự kiện, {} đơn...", plans.size(), props.orders());
        long used = 0;
        for (EventPlan plan : plans) {
            used += plan.plan();
        }
        double extraRate = used == 0 ? 0 : Math.max(0, (double) (props.checkins() - used) / used);
        log.info("Kế hoạch xong sau {} s: {} vé sẽ vào cổng, thêm trung bình {} lượt quét mỗi vé",
                seconds(started), used, "%.2f".formatted(extraRate));

        partitions.ensure(YearMonth.from(now.minus(HISTORY).minus(Duration.ofDays(31)).atOffset(ZoneOffset.UTC)),
                YearMonth.from(now.plus(AHEAD).plus(Duration.ofDays(31)).atOffset(ZoneOffset.UTC)));
        writeVenues(plans);

        AtomicInteger done = new AtomicInteger();
        AtomicLong rows = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(props.threads());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (EventPlan plan : plans) {   // sự kiện lớn đứng đầu danh sách: bắt đầu sớm nhất
                futures.add(pool.submit(() -> {
                    rows.addAndGet(writeEvent(plan, extraRate));
                    int n = done.incrementAndGet();
                    if (n % 100 == 0 || n == plans.size()) {
                        log.info("Đã ghi {}/{} sự kiện, {} dòng, {} s", n, plans.size(), rows.get(), seconds(started));
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        log.info("Tính bảng tổng hợp doanh thu và hạn mức mua...");
        salesRollup.rebuildAll();
        jdbc.update("""
                INSERT INTO purchase_quotas (user_id, event_id, reserved)
                SELECT o.user_id, o.event_id, sum(oi.quantity)
                FROM orders o
                JOIN events e ON e.id = o.event_id
                JOIN order_items oi ON oi.order_id = o.id
                WHERE e.sale_closes_at > ? AND o.status IN ('PENDING_PAYMENT', 'PAID', 'FULFILLED')
                GROUP BY o.user_id, o.event_id
                ON CONFLICT (user_id, event_id) DO NOTHING
                """, java.sql.Timestamp.from(now));
        jdbc.execute("ANALYZE");
        log.info("Xong: {} sự kiện, {} dòng trong {} s. Sự kiện lớn: {} ({})", plans.size(), rows.get(),
                seconds(started), plans.getFirst().title, plans.getFirst().id);
    }

    /** Sự kiện 0 là sự kiện lớn đang mở bán; các sự kiện khác rải từ 2 năm trước tới 3 tháng sau. */
    private List<EventPlan> plans(Instant now) {
        int events = Math.max(1, props.events());
        List<EventPlan> plans = new ArrayList<>(events);
        Instant bigStart = now.truncatedTo(ChronoUnit.DAYS).plus(Duration.ofDays(30)).plus(Duration.ofHours(12));
        plans.add(new EventPlan(0, props.seed(), ORGANIZER_SKY, venueOf(0), CITIES[0], bigStart,
                now.minus(Duration.ofDays(14)), now, Math.max(0, Math.min(props.bigEventOrders(), props.customers())),
                props.customers(),
                props.staff(), true));
        if (events == 1) {
            return plans;
        }
        // Chỉ sự kiện đã mở bán mới có đơn; chia đều số đơn còn lại cho chúng (lệch ±50% để có sự kiện đông, vắng)
        long span = HISTORY.plus(AHEAD).getSeconds();
        List<Instant> starts = new ArrayList<>();
        int onSale = 0;
        for (int e = 1; e < events; e++) {
            Instant start = now.minus(HISTORY).plusSeconds(span * (e - 1) / Math.max(1, events - 2))
                    .truncatedTo(ChronoUnit.DAYS).plus(Duration.ofHours(12));
            starts.add(start);
            if (!start.minus(Duration.ofDays(45)).isAfter(now.minus(Duration.ofHours(1)))) {
                onSale++;
            }
        }
        long remaining = Math.max(0, props.orders() - props.bigEventOrders());
        long perEvent = onSale == 0 ? 0 : remaining / onSale;
        long assigned = 0;
        int seen = 0;
        for (int e = 1; e < events; e++) {
            Instant start = starts.get(e - 1);
            Instant opens = start.minus(Duration.ofDays(45));
            int orders = 0;
            if (!opens.isAfter(now.minus(Duration.ofHours(1)))) {
                seen++;
                long n = seen == onSale ? remaining - assigned
                        : Math.round(perEvent * (0.5 + new java.util.SplittableRandom(props.seed() + e).nextDouble()));
                n = Math.min(Math.max(0, n), Math.min(remaining - assigned, props.customers()));
                orders = (int) n;
                assigned += n;
            }
            int organizer = 1 + (e - 1) % Math.max(1, props.organizers());
            plans.add(new EventPlan(e, props.seed(), DatagenIds.organizer(organizer), venueOf(organizer),
                    CITIES[organizer % CITIES.length], start, opens, now, orders, props.customers(), props.staff(),
                    false));
        }
        return plans;
    }

    private boolean alreadyGenerated(EventPlan first) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM events WHERE id = ?", Integer.class, first.id);
        return n != null && n > 0;
    }

    private static java.util.UUID venueOf(int organizer) {
        return DatagenIds.of(EventPlan.TAG_VENUE, organizer, 0);
    }

    /** Mỗi ban tổ chức một địa điểm; địa điểm số 0 là sân Mỹ Đình của Sky (sự kiện lớn). */
    private void writeVenues(List<EventPlan> plans) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (CopyWriter venues = new CopyWriter(c, "venues", "id, organizer_id, name, address, city, created_at")) {
                Instant created = plans.getFirst().now.minus(HISTORY).minus(Duration.ofDays(60));
                venues.row(venueOf(0), ORGANIZER_SKY, "Sân vận động Mỹ Đình", "Lê Đức Thọ, Nam Từ Liêm", CITIES[0],
                        created);
                for (int o = 1; o <= props.organizers(); o++) {
                    String city = CITIES[o % CITIES.length];
                    venues.row(venueOf(o), DatagenIds.organizer(o), "Nhà hát " + city + " số " + o,
                            o + " Đường Lớn, " + city, city, created);
                }
            }
            c.commit();
        }
    }

    /** Một sự kiện, một transaction. Trả số dòng đã ghi. */
    private long writeEvent(EventPlan plan, double extraRate) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                // Không chờ ghi WAL xuống đĩa lúc commit: nhanh hơn, mất điện thì mất vài transaction cuối (máy dev)
                s.execute("SET LOCAL synchronous_commit = off");
            }
            long rows = writeCatalog(c, plan) + writeOrders(c, plan) + writeSeats(c, plan) + writeItems(c, plan)
                    + writePayments(c, plan) + writeTickets(c, plan) + writeStaff(c, plan)
                    + writeCheckins(c, plan, extraRate);
            c.commit();
            return rows;
        }
    }

    private long writeCatalog(Connection c, EventPlan plan) throws SQLException {
        Instant created = plan.saleOpensAt.minus(Duration.ofDays(7));
        try (CopyWriter events = new CopyWriter(c, "events", "id, organizer_id, venue_id, title, time_zone, starts_at, "
                + "sale_opens_at, sale_closes_at, status, search_text, max_tickets_per_user, created_at, updated_at")) {
            events.row(plan.id, plan.organizerId, plan.venueId, plan.title, "Asia/Ho_Chi_Minh", plan.startsAt,
                    plan.saleOpensAt, plan.saleClosesAt, "PUBLISHED", VietnameseText.normalize(plan.title), 4,
                    created, created);
        }
        try (CopyWriter types = new CopyWriter(c, "ticket_types",
                "id, event_id, name, kind, price, total_quantity, available_quantity, created_at")) {
            for (TypePlan t : plan.types) {
                boolean ga = t.kind == TicketKind.GA;
                types.row(typeId(plan, t), plan.id, t.name, t.kind.name(), t.price, ga ? t.capacity : null,
                        ga ? t.capacity - t.sold : null, created);
            }
        }
        return 1 + plan.types.size();
    }

    private long writeOrders(Connection c, EventPlan plan) throws SQLException {
        try (CopyWriter orders = new CopyWriter(c, "orders", "id, user_id, event_id, status, total_amount, currency, "
                + "idempotency_key, request_hash, expires_at, paid_at, fulfilled_at, created_at, updated_at, "
                + "sales_channel")) {
            for (int k = 0; k < plan.orders; k++) {
                OrderSpec o = plan.order(k);
                TypePlan type = plan.types.get(o.typeIndex());
                Instant updated = o.fulfilledAt() != null ? o.fulfilledAt() : o.createdAt().plusSeconds(600);
                orders.row(o.id(), o.buyer(), plan.id, o.status().name(),
                        type.price.multiply(java.math.BigDecimal.valueOf(o.quantity())), "VND",
                        "dg-" + plan.index + "-" + k, "%064x".formatted(o.id().getLeastSignificantBits()),
                        o.createdAt().plusSeconds(600), o.paidAt(), o.fulfilledAt(), o.createdAt(), updated,
                        o.channel().name());
            }
            return orders.rows();
        }
    }

    /** Ghế của từng loại vé có ghế: ghế đầu bán lần lượt cho các đơn (SOLD), phần còn lại trống. */
    private long writeSeats(Connection c, EventPlan plan) throws SQLException {
        try (CopyWriter seats = new CopyWriter(c, "seats",
                "id, event_id, ticket_type_id, section, row_label, seat_number, status, order_id")) {
            int[] next = new int[plan.types.size()];
            for (int k = 0; k < plan.orders; k++) {
                OrderSpec o = plan.order(k);
                TypePlan type = plan.types.get(o.typeIndex());
                if (type.kind != TicketKind.SEATED || o.status() != OrderStatus.FULFILLED) {
                    continue;
                }
                for (int j = 0; j < o.quantity(); j++) {
                    seat(seats, plan, type, next[type.index]++, o);
                }
            }
            for (TypePlan type : plan.types) {
                if (type.kind == TicketKind.SEATED) {
                    for (int n = next[type.index]; n < type.capacity; n++) {
                        seat(seats, plan, type, n, null);
                    }
                }
            }
            return seats.rows();
        }
    }

    /** Ghế thứ n của loại vé: khu 1.000 ghế, hàng 25 ghế (A, B, ... Z, AA...). */
    private static void seat(CopyWriter seats, EventPlan plan, TypePlan type, int n, OrderSpec buyer)
            throws SQLException {
        seats.row(seatId(plan, type, n), plan.id, typeId(plan, type), type.name + " " + (n / 1000 + 1),
                rowLabel((n % 1000) / 25), n % 25 + 1, buyer == null ? "AVAILABLE" : "SOLD",
                buyer == null ? null : buyer.id());
    }

    private long writeItems(Connection c, EventPlan plan) throws SQLException {
        try (CopyWriter items = new CopyWriter(c, "order_items",
                "id, order_id, ticket_type_id, seat_id, quantity, unit_price")) {
            int[] next = new int[plan.types.size()];
            for (int k = 0; k < plan.orders; k++) {
                OrderSpec o = plan.order(k);
                TypePlan type = plan.types.get(o.typeIndex());
                if (type.kind == TicketKind.SEATED) {   // vé có ghế: mỗi ghế một dòng, quantity 1
                    for (int j = 0; j < o.quantity(); j++) {
                        items.row(itemId(plan, o, j), o.id(), typeId(plan, type),
                                seatId(plan, type, next[type.index]++), 1, type.price);
                    }
                } else {
                    items.row(itemId(plan, o, 0), o.id(), typeId(plan, type), null, o.quantity(), type.price);
                }
            }
            return items.rows();
        }
    }

    private long writePayments(Connection c, EventPlan plan) throws SQLException {
        try (CopyWriter payments = new CopyWriter(c, "payments", "id, order_id, provider, status, amount, currency, "
                + "gateway_payment_id, gateway_transaction_id, expires_at, succeeded_at, created_at, updated_at")) {
            for (int k = 0; k < plan.orders; k++) {
                OrderSpec o = plan.order(k);
                if (o.status() != OrderStatus.FULFILLED) {
                    continue;
                }
                TypePlan type = plan.types.get(o.typeIndex());
                Instant created = o.createdAt().plusSeconds(20);
                payments.row(DatagenIds.of(EventPlan.TAG_PAYMENT, plan.index, k), o.id(), Payment.PROVIDER_MOCKPAY,
                        "SUCCEEDED", type.price.multiply(java.math.BigDecimal.valueOf(o.quantity())), "VND",
                        "dgp_" + plan.index + "_" + k, "dgt_" + plan.index + "_" + k, created.plusSeconds(900),
                        o.paidAt(), created, o.paidAt());
            }
            return payments.rows();
        }
    }

    private long writeTickets(Connection c, EventPlan plan) throws SQLException {
        try (CopyWriter tickets = new CopyWriter(c, "tickets", "id, order_id, order_item_id, item_seq, event_id, "
                + "ticket_type_id, seat_id, owner_user_id, status, token_version, issued_at, checked_in_at, "
                + "checked_in_gate, checked_in_by")) {
            int[] next = new int[plan.types.size()];
            for (int k = 0; k < plan.orders; k++) {
                OrderSpec o = plan.order(k);
                if (o.status() != OrderStatus.FULFILLED) {
                    continue;
                }
                TypePlan type = plan.types.get(o.typeIndex());
                boolean seated = type.kind == TicketKind.SEATED;
                for (int j = 0; j < o.quantity(); j++) {
                    TicketSpec t = plan.ticket(o, j);
                    tickets.row(t.id(), o.id(), itemId(plan, o, seated ? j : 0), seated ? 1 : j + 1, plan.id,
                            typeId(plan, type), seated ? seatId(plan, type, next[type.index]++) : null, o.buyer(),
                            t.checkedInAt() == null ? "VALID" : "USED", 1, o.fulfilledAt(), t.checkedInAt(), t.gate(),
                            t.staffId());
                }
            }
            return tickets.rows();
        }
    }

    private long writeStaff(Connection c, EventPlan plan) throws SQLException {
        try (CopyWriter staff = new CopyWriter(c, "event_staff", "event_id, user_id")) {
            for (java.util.UUID id : new java.util.LinkedHashSet<>(plan.staff)) {
                staff.row(plan.id, id);
            }
            return staff.rows();
        }
    }

    /** Lượt quét hợp lệ của mỗi vé đã vào cổng, cộng các lượt quét lại, QR cũ, QR hỏng. */
    private long writeCheckins(Connection c, EventPlan plan, double extraRate) throws SQLException {
        if (!plan.isPast()) {
            return 0;
        }
        try (CopyWriter logs = new CopyWriter(c, "checkin_logs",
                "ticket_id, event_id, staff_user_id, gate, result, scanned_at")) {
            for (int k = 0; k < plan.orders; k++) {
                OrderSpec o = plan.order(k);
                if (o.status() != OrderStatus.FULFILLED) {
                    continue;
                }
                for (int j = 0; j < o.quantity(); j++) {
                    TicketSpec t = plan.ticket(o, j);
                    if (t.checkedInAt() == null) {
                        continue;
                    }
                    logs.row(t.id(), plan.id, t.staffId(), t.gate(), "OK", t.checkedInAt());
                    for (ScanSpec scan : plan.extraScans(o, j, t, extraRate)) {
                        logs.row(scan.ticketId(), scan.eventId(), scan.staffId(), scan.gate(), scan.result().name(),
                                scan.scannedAt());
                    }
                }
            }
            return logs.rows();
        }
    }

    private static java.util.UUID typeId(EventPlan plan, TypePlan type) {
        return DatagenIds.of(EventPlan.TAG_TICKET_TYPE, plan.index, type.index);
    }

    private static java.util.UUID seatId(EventPlan plan, TypePlan type, int n) {
        return DatagenIds.of(EventPlan.TAG_SEAT, plan.index, (long) type.index << 32 | n);
    }

    private static java.util.UUID itemId(EventPlan plan, OrderSpec order, int j) {
        return DatagenIds.of(EventPlan.TAG_ITEM, plan.index, (long) order.k() * 8 + j);
    }

    /** 0 → A, 25 → Z, 26 → AA. */
    static String rowLabel(int index) {
        StringBuilder label = new StringBuilder();
        int i = index;
        do {
            label.insert(0, (char) ('A' + i % 26));
            i = i / 26 - 1;
        } while (i >= 0);
        return label.toString();
    }

    private static long seconds(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000_000;
    }
}
