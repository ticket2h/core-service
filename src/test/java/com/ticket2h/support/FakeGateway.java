package com.ticket2h.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cổng thanh toán giả cho integration test của {@code app}, chạy bằng HTTP server của JDK nên không
 * cần khởi động MockPay thật. Lập trình được: trả 500, chậm, 503 khi hoàn tiền.
 */
public final class FakeGateway {

    public static final String API_KEY = "test-api-key";
    public static final FakeGateway INSTANCE = start();

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, String> sessions = new ConcurrentHashMap<>();
    private final List<JsonNode> createRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> refundRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger createFailures = new AtomicInteger();
    private final AtomicInteger refundFailures = new AtomicInteger();
    private volatile long createDelayMs;

    private FakeGateway(HttpServer server) {
        this.server = server;
    }

    private static FakeGateway start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            FakeGateway gateway = new FakeGateway(server);
            server.createContext("/api/v1/payments", gateway::handleCreate);
            server.createContext("/api/v1/refunds", gateway::handleRefund);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            return gateway;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void reset() {
        sessions.clear();
        createRequests.clear();
        refundRequests.clear();
        createFailures.set(0);
        refundFailures.set(0);
        createDelayMs = 0;
    }

    /** {@code n} lần tạo phiên tiếp theo trả HTTP 500. */
    public void failNextCreates(int n) {
        createFailures.set(n);
    }

    /** Tạo phiên chậm {@code ms} mili giây (vượt read-timeout của app thì app coi là lỗi). */
    public void delayCreates(long ms) {
        createDelayMs = ms;
    }

    /** {@code n} lần hoàn tiền tiếp theo trả HTTP 503. */
    public void failNextRefunds(int n) {
        refundFailures.set(n);
    }

    public List<JsonNode> createRequests() {
        return createRequests;
    }

    public List<JsonNode> refundRequests() {
        return refundRequests;
    }

    public String gatewayPaymentIdFor(String merchantRef) {
        return "mp_" + merchantRef.replace("-", "");
    }

    private void handleCreate(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
        createRequests.add(body);
        if (createDelayMs > 0) {
            sleep(createDelayMs);
        }
        if (createFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            respond(exchange, 500, "{\"code\":\"INTERNAL\"}");
            return;
        }
        String merchantRef = body.get("merchantRef").asString();
        String gatewayId = gatewayPaymentIdFor(merchantRef);
        boolean existed = sessions.putIfAbsent(merchantRef, gatewayId) != null;
        respond(exchange, existed ? 200 : 201, json.writeValueAsString(Map.of(
                "gatewayPaymentId", gatewayId,
                "paymentUrl", baseUrl() + "/pay/" + gatewayId,
                "expiresAt", "2099-01-01T00:00:00Z")));
    }

    private void handleRefund(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
        refundRequests.add(body);
        if (refundFailures.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            respond(exchange, 503, "{\"code\":\"UNAVAILABLE\"}");
            return;
        }
        respond(exchange, 200, json.writeValueAsString(Map.of(
                "refundId", "rf_" + body.get("idempotencyKey").asString(), "status", "SUCCEEDED")));
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        if (!API_KEY.equals(exchange.getRequestHeaders().getFirst("X-Api-Key"))) {
            respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
            return false;
        }
        return true;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
