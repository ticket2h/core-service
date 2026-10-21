package com.ticket2h.support;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Gọi API thật qua HTTP (app chạy ở cổng ngẫu nhiên). Không ném exception với mã 4xx/5xx. */
public class Api {

    private final RestClient rest;
    private final JsonMapper json;

    public Api(int port, JsonMapper json) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.rest = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .requestFactory(factory)
                .defaultStatusHandler(status -> true, (request, response) -> { })
                .build();
        this.json = json;
    }

    public Response get(String path, String token) {
        return send(HttpMethod.GET, path, token, null, Map.of());
    }

    public Response get(String path, String token, Map<String, String> headers) {
        return send(HttpMethod.GET, path, token, null, headers);
    }

    public Response post(String path, String token, Object body) {
        return send(HttpMethod.POST, path, token, body, Map.of());
    }

    public Response post(String path, String token, Object body, Map<String, String> headers) {
        return send(HttpMethod.POST, path, token, body, headers);
    }

    public Response put(String path, String token, Object body) {
        return send(HttpMethod.PUT, path, token, body, Map.of());
    }

    public Response delete(String path, String token) {
        return send(HttpMethod.DELETE, path, token, null, Map.of());
    }

    /** Gửi body thô (không serialize lại), dùng cho webhook. */
    public Response postRaw(String path, byte[] body, Map<String, String> headers) {
        return exchange(rest.post().uri(path).contentType(MediaType.APPLICATION_JSON)
                .headers(h -> headers.forEach(h::set)).body(body));
    }

    /** Body thô với Content-Type tuỳ ý (file CSV), kèm token. */
    public Response postBytes(String path, String token, byte[] body, String contentType) {
        return exchange(rest.post().uri(path).contentType(MediaType.parseMediaType(contentType))
                .headers(h -> auth(h, token)).body(body));
    }

    public ResponseEntity<byte[]> getBytes(String path, String token) {
        return rest.get().uri(path).headers(h -> auth(h, token)).retrieve().toEntity(byte[].class);
    }

    private Response send(HttpMethod method, String path, String token, Object body, Map<String, String> headers) {
        RestClient.RequestBodySpec spec = rest.method(method).uri(path)
                .headers(h -> {
                    auth(h, token);
                    headers.forEach(h::set);
                });
        if (body != null) {
            spec.contentType(MediaType.APPLICATION_JSON)
                    .body(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return exchange(spec);
    }

    private Response exchange(RestClient.RequestHeadersSpec<?> spec) {
        ResponseEntity<String> entity = spec.retrieve().toEntity(String.class);
        return new Response(entity.getStatusCode().value(), entity.getHeaders(), entity.getBody(), json);
    }

    private static void auth(HttpHeaders headers, String token) {
        if (token != null) {
            headers.setBearerAuth(token);
        }
    }

    public record Response(int status, HttpHeaders headers, String body, JsonMapper mapper) {

        public JsonNode json() {
            return body == null || body.isEmpty() ? mapper.createObjectNode() : mapper.readTree(body);
        }

        public String code() {
            JsonNode code = json().get("code");
            return code == null ? null : code.asString();
        }

        public String header(String name) {
            return headers.getFirst(name);
        }
    }
}
