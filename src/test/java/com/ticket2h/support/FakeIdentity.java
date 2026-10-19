package com.ticket2h.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.ticket2h.common.security.Role;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Thay identity-service trong test: HTTP server của JDK phục vụ JWKS ({@code /.well-known/jwks.json}, public key
 * của {@link TestKeys#JWT}), {@code POST /internal/users/lookup} và {@code GET /internal/users/{id}} theo đúng hợp
 * đồng (infra/docs/contracts.md).
 * Tài khoản test nằm trong bộ nhớ ở đây ({@link TestData#user()}), không còn trong database của core.
 * Một instance cho cả JVM.
 */
public final class FakeIdentity {

    /** Phải giống ticket2h.identity-api.api-key trong application-test.yml. */
    public static final String API_KEY = "test-internal-key-core-0123456789abcdef";
    public static final String KEY_ID = "test-k1";

    public static final FakeIdentity INSTANCE = new FakeIdentity();

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String jwks;
    private final Map<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final Map<String, UUID> idsByEmail = new ConcurrentHashMap<>();
    private final AtomicInteger failuresRemaining = new AtomicInteger();
    private final AtomicInteger lookups = new AtomicInteger();
    private volatile boolean jwksDown;

    private FakeIdentity() {
        RSAKey key = new RSAKey.Builder((RSAPublicKey) TestKeys.JWT.getPublic())
                .keyID(KEY_ID).keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.RS256).build();
        this.jwks = new JWKSet(key).toString();   // chỉ phần public
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/.well-known/jwks.json", exchange -> {
            try (exchange) {
                if (jwksDown) {
                    respondJson(exchange, 503, Map.of("code", "UNAVAILABLE"));
                } else {
                    respond(exchange, 200, jwks.getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.createContext("/internal/users/lookup", this::lookup);
        server.createContext("/internal/users/", this::byId);   // context dài hơn (lookup) được chọn trước
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String jwkSetUri() {
        return baseUrl() + "/.well-known/jwks.json";
    }

    public void reset() {
        accounts.clear();
        idsByEmail.clear();
        failuresRemaining.set(0);
        lookups.set(0);
        jwksDown = false;
    }

    /** JWKS trả 503, như khi identity-service tắt. Chỉ có tác dụng với core chưa lấy được JWKS lần nào. */
    public void jwksDown(boolean down) {
        this.jwksDown = down;
    }

    public void add(Account account) {
        accounts.put(account.id(), account);
        idsByEmail.put(account.email().toLowerCase(Locale.ROOT), account.id());
    }

    public Optional<Account> find(UUID id) {
        return Optional.ofNullable(accounts.get(id));
    }

    /** Xoá tài khoản, như khi identity xoá người dùng: tra theo id hay email đều 404. */
    public void remove(UUID id) {
        Account removed = accounts.remove(id);
        if (removed != null) {
            idsByEmail.remove(removed.email().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * {@code n} lời gọi API nội bộ tới ({@code /internal/users/**}: tra email hoặc tra id) trả 503, như khi
     * identity-service đang khởi động lại. JWKS không bị ảnh hưởng.
     */
    public void failNext(int n) {
        failuresRemaining.set(n);
    }

    public int lookups() {
        return lookups.get();
    }

    private void lookup(HttpExchange exchange) throws IOException {
        try (exchange) {
            lookups.incrementAndGet();
            if (!available(exchange) || !authorized(exchange)) {
                return;
            }
            JsonNode body = json.readTree(exchange.getRequestBody());
            String email = body.path("email").asString("").trim().toLowerCase(Locale.ROOT);
            UUID id = idsByEmail.get(email);
            Account a = id == null ? null : accounts.get(id);
            if (a == null) {
                respondJson(exchange, 404, Map.of("code", "NOT_FOUND"));
                return;
            }
            respondJson(exchange, 200, userJson(a));
        }
    }

    /** {@code GET /internal/users/{id}}. Không có tài khoản, hoặc id không phải UUID: 404 như identity thật. */
    private void byId(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respondJson(exchange, 405, Map.of("code", "METHOD_NOT_ALLOWED"));
                return;
            }
            if (!available(exchange) || !authorized(exchange)) {
                return;
            }
            String rawId = exchange.getRequestURI().getPath().substring("/internal/users/".length());
            Account a;
            try {
                a = accounts.get(UUID.fromString(rawId));
            } catch (IllegalArgumentException e) {
                a = null;
            }
            if (a == null) {
                respondJson(exchange, 404, Map.of("code", "NOT_FOUND"));
                return;
            }
            respondJson(exchange, 200, userJson(a));
        }
    }

    /** Còn lượt lỗi do {@link #failNext} thì trả 503. */
    private boolean available(HttpExchange exchange) throws IOException {
        if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            respondJson(exchange, 503, Map.of("code", "UNAVAILABLE"));
            return false;
        }
        return true;
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        if (!API_KEY.equals(exchange.getRequestHeaders().getFirst("X-Internal-Api-Key"))) {
            respondJson(exchange, 401, Map.of("code", "UNAUTHENTICATED"));
            return false;
        }
        return true;
    }

    private static Map<String, Object> userJson(Account a) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", a.id().toString());
        user.put("email", a.email());
        user.put("fullName", a.fullName());
        user.put("role", a.role().name());
        user.put("organizerId", a.organizerId() == null ? null : a.organizerId().toString());
        return user;
    }

    private void respondJson(HttpExchange exchange, int status, Object body) throws IOException {
        respond(exchange, status, json.writeValueAsBytes(body));
    }

    private static void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", status < 400 ? "application/json" : "application/problem+json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** Tài khoản như identity-service lưu (trừ mật khẩu: core không bao giờ thấy mật khẩu). */
    public record Account(UUID id, String email, String fullName, Role role, UUID organizerId) {}
}
