package com.ticket2h.support;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ticket2h.common.config.Ticket2hProperties;
import com.ticket2h.common.security.JwtClaims;
import com.ticket2h.common.security.Role;
import com.ticket2h.common.time.Times;
import com.ticket2h.ordering.CreateOrderRequest;
import com.ticket2h.ordering.OrderPlacement;
import com.ticket2h.payment.Payment;
import com.ticket2h.payment.PaymentInitiation;
import com.ticket2h.ticketing.QrTokenCodec;
import com.ticket2h.ticketing.TicketClaims;

import tools.jackson.databind.json.JsonMapper;

/**
 * Tạo dữ liệu test bằng JDBC (nhanh, không đi qua API). Tài khoản nằm ở identity-service, nên ở đây tài khoản
 * được đăng ký vào {@link FakeIdentity} và token được ký bằng khoá test giống cách identity ký (RS256).
 * Bean trong test, khai báo qua {@code @Import} ở {@link IntegrationTest}.
 */
public class TestData {

    private final JdbcTemplate jdbc;
    private final MutableClock clock;
    private final Ticket2hProperties properties;
    private final OrderPlacement placement;
    private final PaymentInitiation paymentInitiation;
    private final JsonMapper json;
    private final QrTokenCodec qrCodec;
    private final AtomicLong sequence = new AtomicLong();
    private final FakeIdentity identity = FakeIdentity.INSTANCE;
    private volatile Api api;

    public TestData(JdbcTemplate jdbc, MutableClock clock, Ticket2hProperties properties, OrderPlacement placement,
            PaymentInitiation paymentInitiation, JsonMapper json, QrTokenCodec qrCodec) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.placement = placement;
        this.paymentInitiation = paymentInitiation;
        this.json = json;
        this.qrCodec = qrCodec;
    }

    void useApi(Api api) {
        this.api = api;
    }

    // ---------- users ----------

    public UUID user() {
        return user(Role.CUSTOMER);
    }

    public UUID user(Role role) {
        return userWithEmail("u" + sequence.incrementAndGet() + "@test.local", role, null);
    }

    public UUID userWithEmail(String email, Role role, UUID organizerId) {
        UUID id = UUID.randomUUID();
        identity.add(new FakeIdentity.Account(id, email, "Người dùng " + email, role, organizerId));
        return id;
    }

    /** {@code n} khách hàng (chỉ trong bộ nhớ của FakeIdentity, nhanh kể cả 2.500 người). */
    public List<UUID> users(int n) {
        List<UUID> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(userWithEmail("u" + sequence.incrementAndGet() + "@test.local", Role.CUSTOMER, null));
        }
        return ids;
    }

    public String emailOf(UUID userId) {
        return account(userId).email();
    }

    /** JWT cho user, ký RS256 bằng khoá test mà FakeIdentity công bố qua JWKS (như identity-service thật). */
    public String token(UUID userId) {
        FakeIdentity.Account account = account(userId);
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofDays(1)))
                .claim(JwtClaims.ROLE, account.role().name());
        if (account.organizerId() != null) {
            claims.claim(JwtClaims.ORGANIZER_ID, account.organizerId().toString());
        }
        return sign(TestKeys.JWT, FakeIdentity.KEY_ID, claims.build());
    }

    /** Ký JWT RS256 bằng {@code key} bất kỳ, để test token giả mạo. */
    public static String sign(KeyPair key, String keyId, JwtClaimsSet claims) {
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) key.getPublic())
                .privateKey((RSAPrivateKey) key.getPrivate()).keyID(keyId).build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build(),
                claims)).getTokenValue();
    }

    private FakeIdentity.Account account(UUID userId) {
        return identity.find(userId).orElseThrow(() -> new IllegalArgumentException("Không có user test " + userId));
    }

    /** Một ban tổ chức kèm một tài khoản ORGANIZER (ban tổ chức chỉ là một id: dữ liệu nằm ở identity). */
    public OrganizerRef organizer() {
        UUID organizerId = UUID.randomUUID();
        String email = "org" + sequence.incrementAndGet() + "@test.local";
        UUID userId = userWithEmail(email, Role.ORGANIZER, organizerId);
        return new OrganizerRef(organizerId, userId, email);
    }

    // ---------- catalog ----------

    /** Sự kiện PUBLISHED đang mở bán theo {@code clock}. */
    public EventRef eventOnSale(Consumer<EventSpec> customizer) {
        EventSpec spec = new EventSpec(clock.instant());
        customizer.accept(spec);
        OrganizerRef organizer = organizer();
        UUID venueId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO venues (id, organizer_id, name, address, city, created_at)
                VALUES (?, ?, 'Nhà hát Lớn', '1 Tràng Tiền', 'Hà Nội', ?)
                """, venueId, organizer.organizerId(), Times.utc(clock.instant()));
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, organizer_id, venue_id, title, time_zone, starts_at, sale_opens_at,
                                    sale_closes_at, status, search_text, max_tickets_per_user, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'Asia/Ho_Chi_Minh', ?, ?, ?, ?, ?, ?, ?, ?)
                """, eventId, organizer.organizerId(), venueId, spec.title, Times.utc(spec.startsAt),
                Times.utc(spec.saleOpensAt), Times.utc(spec.saleClosesAt), spec.status,
                spec.title.toLowerCase(), spec.maxTicketsPerUser, Times.utc(clock.instant()),
                Times.utc(clock.instant()));
        return new EventRef(eventId, organizer, venueId, spec.startsAt);
    }

    public UUID seatedTicketType(EventRef event, BigDecimal price) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ticket_types (id, event_id, name, kind, price, created_at)
                VALUES (?, ?, ?, 'SEATED', ?, ?)
                """, id, event.id(), "VIP " + sequence.incrementAndGet(), price, Times.utc(clock.instant()));
        return id;
    }

    public UUID gaTicketType(EventRef event, BigDecimal price, int quantity) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ticket_types (id, event_id, name, kind, price, total_quantity, available_quantity, created_at)
                VALUES (?, ?, ?, 'GA', ?, ?, ?, ?)
                """, id, event.id(), "GA " + sequence.incrementAndGet(), price, quantity, quantity,
                Times.utc(clock.instant()));
        return id;
    }

    /** {@code count} ghế mới trên một hàng mới của sự kiện. */
    public List<UUID> seats(EventRef event, UUID ticketTypeId, int count) {
        String row = "R" + sequence.incrementAndGet();
        List<UUID> ids = new ArrayList<>(count);
        List<Object[]> rows = new ArrayList<>(count);
        for (int n = 1; n <= count; n++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            rows.add(new Object[] {id, event.id(), ticketTypeId, "S", row, n});
        }
        jdbc.batchUpdate("""
                INSERT INTO seats (id, event_id, ticket_type_id, section, row_label, seat_number)
                VALUES (?, ?, ?, ?, ?, ?)
                """, rows);
        return ids;
    }

    /** Nhân viên STAFF được phân công soát vé sự kiện. */
    public UUID assignedStaff(EventRef event) {
        UUID staff = user(Role.STAFF);
        jdbc.update("INSERT INTO event_staff (event_id, user_id) VALUES (?, ?)", event.id(), staff);
        return staff;
    }

    // ---------- orders, payments ----------

    /** Đơn PENDING_PAYMENT rỗng (không item) của {@code n} user khác nhau, dùng cho test giữ ghế/GA. */
    public List<UUID> pendingOrders(EventRef event, int n) {
        List<UUID> buyers = users(n);
        List<UUID> orders = new ArrayList<>(n);
        List<Object[]> rows = new ArrayList<>(n);
        Instant now = clock.instant();
        for (UUID buyer : buyers) {
            UUID id = UUID.randomUUID();
            orders.add(id);
            rows.add(new Object[] {id, buyer, event.id(), UUID.randomUUID().toString(), "0".repeat(64),
                    Times.utc(now.plus(Duration.ofMinutes(10))), Times.utc(now), Times.utc(now)});
        }
        jdbc.batchUpdate("""
                INSERT INTO orders (id, user_id, event_id, status, total_amount, currency, idempotency_key,
                                    request_hash, expires_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING_PAYMENT', 0, 'VND', ?, ?, ?, ?, ?)
                """, rows);
        return orders;
    }

    public OrderPlacement.PlacementResult placeOrder(UUID buyer, CreateOrderRequest request) {
        return placement.place(buyer, UUID.randomUUID().toString(), request);
    }

    public Payment initiatePayment(UUID buyer, UUID orderId) {
        return paymentInitiation.initiate(buyer, orderId).payment();
    }

    /** Tạo đơn qua OrderPlacement, tạo payment, gửi webhook thành công đã ký. Trả orderId. */
    public UUID paidOrderViaWebhook(UUID buyer, CreateOrderRequest request) {
        UUID orderId = placeOrder(buyer, request).order().getId();
        Payment payment = initiatePayment(buyer, orderId);
        Api.Response response = sendWebhook(succeededWebhook(payment));
        if (response.status() != 200) {
            throw new IllegalStateException("Webhook thất bại: " + response.status() + " " + response.body());
        }
        return orderId;
    }

    /** Thanh toán đơn rồi chờ consumer phát vé (tối đa 10 giây). Trả id vé theo thứ tự phát. */
    public List<UUID> issuedTickets(UUID buyer, CreateOrderRequest request) {
        UUID orderId = paidOrderViaWebhook(buyer, request);
        int expected = request.items().stream()
                .mapToInt(item -> item.hasSeats() ? item.seatIds().size() : item.quantity()).sum();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM tickets WHERE order_id = ?", Integer.class, orderId) == expected);
        return jdbc.queryForList("SELECT id FROM tickets WHERE order_id = ? ORDER BY order_item_id, item_seq",
                UUID.class, orderId);
    }

    /** Token QR hiện tại của vé (theo token_version trong database). */
    public String qrToken(UUID ticketId) {
        return jdbc.queryForObject("SELECT event_id, token_version FROM tickets WHERE id = ?", (rs, i) ->
                qrCodec.encode(new TicketClaims(ticketId, rs.getObject("event_id", UUID.class),
                        rs.getInt("token_version"))), ticketId);
    }

    // ---------- webhook ----------

    public Map<String, Object> succeededWebhook(Payment payment) {
        return webhook("payment.succeeded", payment.getId(), payment.getGatewayPaymentId(),
                "tx_" + UUID.randomUUID().toString().replace("-", ""), payment.getAmount(), payment.getCurrency());
    }

    public Map<String, Object> failedWebhook(Payment payment) {
        return webhook("payment.failed", payment.getId(), payment.getGatewayPaymentId(), null, payment.getAmount(),
                payment.getCurrency());
    }

    public Map<String, Object> webhook(String type, UUID paymentId, String gatewayPaymentId,
            String gatewayTransactionId, BigDecimal amount, String currency) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "evt_" + UUID.randomUUID().toString().replace("-", ""));
        payload.put("type", type);
        payload.put("gatewayPaymentId", gatewayPaymentId);
        payload.put("gatewayTransactionId", gatewayTransactionId);
        payload.put("merchantRef", paymentId.toString());
        payload.put("amount", amount);
        payload.put("currency", currency);
        payload.put("occurredAt", clock.instant().toString());
        return payload;
    }

    /** POST webhook đã ký bằng secret của app, timestamp theo {@code clock}. */
    public Api.Response sendWebhook(Map<String, Object> payload) {
        return sendSignedWebhook(json.writeValueAsBytes(payload), properties.payment().mockpay().webhookSecret(),
                String.valueOf(payload.get("eventId")));
    }

    public Api.Response sendSignedWebhook(byte[] body, String secret, String eventId) {
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        return api.postRaw("/webhooks/payments/mockpay", body, Map.of(
                "X-MockPay-Event-Id", eventId,
                "X-MockPay-Timestamp", timestamp,
                "X-MockPay-Signature", sign(secret, timestamp, body)));
    }

    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- types ----------

    public record OrganizerRef(UUID organizerId, UUID userId, String email) {}

    public record EventRef(UUID id, OrganizerRef organizer, UUID venueId, Instant startsAt) {}

    public static final class EventSpec {
        String title = "Sự kiện test";
        Instant startsAt;
        Instant saleOpensAt;
        Instant saleClosesAt;
        String status = "PUBLISHED";
        int maxTicketsPerUser = 4;

        EventSpec(Instant now) {
            this.saleOpensAt = now.minus(Duration.ofHours(1));
            this.saleClosesAt = now.plus(Duration.ofDays(7));
            this.startsAt = now.plus(Duration.ofDays(10));
        }

        public EventSpec title(String title) {
            this.title = title;
            return this;
        }

        public EventSpec maxTicketsPerUser(int max) {
            this.maxTicketsPerUser = max;
            return this;
        }

        public EventSpec startsAt(Instant startsAt) {
            this.startsAt = startsAt;
            if (saleClosesAt.isAfter(startsAt)) {
                this.saleClosesAt = startsAt;
            }
            return this;
        }

        public EventSpec saleWindow(Instant opensAt, Instant closesAt) {
            this.saleOpensAt = opensAt;
            this.saleClosesAt = closesAt;
            return this;
        }

        public EventSpec draft() {
            this.status = "DRAFT";
            return this;
        }
    }
}
