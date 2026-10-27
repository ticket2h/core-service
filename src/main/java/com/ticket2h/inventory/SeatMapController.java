package com.ticket2h.inventory;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.ticket2h.catalog.CatalogQueries;
import com.ticket2h.catalog.EventStatus;
import com.ticket2h.common.error.NotFoundException;

import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Sơ đồ ghế công khai. Trả trạng thái hiệu lực: ghế HELD đã quá hạn hiển thị AVAILABLE.
 * Không trả {@code order_id} hay {@code held_until} (không lộ ai đang giữ, giữ đến bao giờ).
 * <p>
 * Cập nhật trực tiếp (ADR-024): mở {@code /seats/stream} TRƯỚC, rồi tải {@code /seats}; áp thay đổi theo
 * {@code version} của từng ghế. Cách ghép hai nguồn: javadoc của {@link SeatMapHub}.
 */
@RestController
class SeatMapController {

    static final String SEQ_HEADER = "Seat-Map-Seq";

    private final SeatMapQueries queries;
    private final SeatMapHub hub;
    private final SeatChangeBus bus;
    private final CatalogQueries catalog;

    SeatMapController(SeatMapQueries queries, SeatMapHub hub, SeatChangeBus bus, CatalogQueries catalog) {
        this.queries = queries;
        this.hub = hub;
        this.bus = bus;
        this.catalog = catalog;
    }

    /**
     * Ảnh chụp, có thể cũ tối đa {@code ticket2h.cache.seat-map-ttl}. Header {@value #SEQ_HEADER}: số thứ tự thay đổi
     * đã nằm trong ảnh chụp. {@code Cache-Control: no-cache} kèm {@code ETag}: trình duyệt luôn hỏi lại, không đổi
     * thì nhận 304 (sơ đồ lớn không phải tải lại vài MB).
     */
    @GetMapping("/events/{eventId}/seats")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
            array = @ArraySchema(schema = @Schema(implementation = SeatMapQueries.SeatView.class))),
            headers = @Header(name = SEQ_HEADER, schema = @Schema(type = "integer", format = "int64")))
    ResponseEntity<byte[]> seats(@PathVariable UUID eventId,
            @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        SeatMapSnapshot snapshot = queries.snapshot(eventId);
        boolean unchanged = snapshot.etag().equals(ifNoneMatch);
        ResponseEntity.BodyBuilder response = ResponseEntity.status(unchanged ? HttpStatus.NOT_MODIFIED : HttpStatus.OK)
                .eTag(snapshot.etag())
                .cacheControl(CacheControl.noCache())
                .header(SEQ_HEADER, String.valueOf(snapshot.seq()));
        return unchanged ? response.build() : response.contentType(MediaType.APPLICATION_JSON).body(snapshot.json());
    }

    /**
     * Luồng SSE các ghế đổi trạng thái (event {@code hello}, {@code seats}, {@code resync}). {@code X-Accel-Buffering}
     * bảo nginx gửi ngay từng event, không gom vào bộ đệm.
     */
    @GetMapping(path = "/events/{eventId}/seats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(@PathVariable UUID eventId) {
        catalog.findEvent(eventId)
                .filter(e -> e.status() == EventStatus.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("sự kiện"));
        SseEmitter emitter = hub.subscribe(eventId, () -> bus.currentSequence(eventId));
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .cacheControl(CacheControl.noStore())
                .body(emitter);
    }
}
