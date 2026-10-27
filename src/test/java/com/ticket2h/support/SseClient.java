package com.ticket2h.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Đọc một luồng SSE thật qua HTTP (như {@code EventSource} của trình duyệt), gom event vào hàng đợi. */
public final class SseClient implements AutoCloseable {

    public record Event(String name, JsonNode data) {}

    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final int status;
    private final Stream<String> lines;

    private SseClient(HttpResponse<Stream<String>> response, JsonMapper json) {
        this.status = response.statusCode();
        this.lines = response.body();
        Thread.ofVirtual().start(() -> read(json));
    }

    public static SseClient open(int port, String path, JsonMapper json) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Accept", "text/event-stream").GET().build();
        try {
            return new SseClient(http.send(request, HttpResponse.BodyHandlers.ofLines()), json);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public int status() {
        return status;
    }

    /** Event kế tiếp có tên {@code name} (bỏ qua event khác), chờ tối đa {@code timeout}. */
    public Event next(String name, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                Event event = left > 0 ? events.poll(left, TimeUnit.NANOSECONDS) : null;
                if (event == null) {
                    throw new AssertionError("Không nhận được event '" + name + "' sau " + timeout);
                }
                if (event.name().equals(name)) {
                    return event;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Mọi event nhận được trong {@code duration} tới. */
    public List<Event> collect(Duration duration) throws InterruptedException {
        Thread.sleep(duration);
        List<Event> drained = new ArrayList<>();
        events.drainTo(drained);
        return drained;
    }

    private void read(JsonMapper json) {
        String name = "message";
        StringBuilder data = new StringBuilder();
        try {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        events.add(new Event(name, json.readTree(data.toString())));
                    }
                    name = "message";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    name = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).stripLeading());
                }
                // dòng bắt đầu bằng ':' là chú thích (keepalive)
            }
        } catch (RuntimeException e) {
            // luồng bị đóng
        }
    }

    @Override
    public void close() {
        lines.close();
    }
}
