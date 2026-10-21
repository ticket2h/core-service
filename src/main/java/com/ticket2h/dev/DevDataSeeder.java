package com.ticket2h.dev;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.ticket2h.catalog.OrganizerCatalogService;
import com.ticket2h.catalog.OrganizerCatalogService.EventFields;
import com.ticket2h.catalog.OrganizerCatalogService.SeatRow;
import com.ticket2h.catalog.TicketKind;

/**
 * Sự kiện mẫu cho chạy thử local (profile {@code dev}). Tài khoản mẫu do identity-service tạo (cũng ở profile dev)
 * với id cố định; core chỉ dùng các id đó, không gọi identity lúc khởi động, nên hai service bật theo thứ tự nào
 * cũng được. Chỉ tạo khi ban tổ chức mẫu chưa có sự kiện nào.
 */
@Component
@Profile("dev")
@Order(1)   // trước công cụ sinh dữ liệu (profile datagen) nếu bật cả hai
class DevDataSeeder implements ApplicationRunner {

    /** Phải giống {@code DevAccounts} của identity-service (infra/docs/contracts.md, mục dữ liệu mẫu). */
    static final UUID ORGANIZER_SKY = UUID.fromString("0a000000-0000-4000-8000-000000000001");
    static final UUID STAFF1 = UUID.fromString("0b000000-0000-4000-8000-000000000003");

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final OrganizerCatalogService catalog;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    DevDataSeeder(OrganizerCatalogService catalog, JdbcTemplate jdbc, Clock clock) {
        this.catalog = catalog;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM events WHERE organizer_id = ?", Integer.class,
                ORGANIZER_SKY);
        if (existing != null && existing > 0) {
            log.info("Dữ liệu mẫu đã có, bỏ qua");
            return;
        }
        var venue = catalog.createVenue(ORGANIZER_SKY, "Sân vận động Mỹ Đình", "Lê Đức Thọ, Nam Từ Liêm", "Hà Nội");
        Instant now = clock.instant().truncatedTo(ChronoUnit.MINUTES);
        var event = catalog.createEvent(ORGANIZER_SKY, new EventFields(venue.id(), "Sơn Tùng M-TP Live Concert 2026",
                "Đêm nhạc Sơn Tùng M-TP tại Mỹ Đình", now.plus(Duration.ofDays(40)),
                now.minus(Duration.ofHours(1)), now.plus(Duration.ofDays(39)), 4));
        var vip = catalog.addTicketType(ORGANIZER_SKY, event.id(), "VIP", TicketKind.SEATED,
                new BigDecimal("1500000"), null);
        catalog.addSeats(ORGANIZER_SKY, event.id(), vip.id(), "VIP",
                List.of(new SeatRow("A", 1, 20), new SeatRow("B", 1, 20), new SeatRow("C", 1, 20)));
        var ga = catalog.addTicketType(ORGANIZER_SKY, event.id(), "GA", TicketKind.GA, new BigDecimal("800000"), 1000);
        catalog.publish(ORGANIZER_SKY, event.id());
        // Phân công staff1 bằng id cố định thay vì catalog.assignStaff(email): cách đó phải gọi identity-service
        jdbc.update("INSERT INTO event_staff (event_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                event.id(), STAFF1);

        log.info("""
                Đã tạo sự kiện mẫu cho ban tổ chức Sky (tài khoản mẫu ở identity-service, mật khẩu Passw0rd!):
                  EVENT_ID={}  GA_TYPE_ID={}  VIP_TYPE_ID={}""", event.id(), ga.id(), vip.id());
    }
}
