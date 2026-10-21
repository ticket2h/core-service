package com.ticket2h.ordering;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SHA-256 của nội dung request đã chuẩn hoá. Sắp xếp item và ghế trước khi băm, để cùng một yêu
 * cầu viết theo thứ tự khác vẫn cho cùng hash. Kênh bán chỉ được thêm vào khi khác WEB: đơn tạo trước khi có
 * trường {@code channel} (M4) giữ nguyên hash, gửi lại cùng key vẫn được trả đơn cũ.
 */
public final class RequestHash {

    public static String of(CreateOrderRequest request) {
        String canonical = request.eventId() + "|" + request.items().stream()
                .sorted(Comparator.comparing((CreateOrderRequest.Item item) -> item.ticketTypeId().toString())
                        .thenComparing(item -> String.valueOf(item.quantity())))
                .map(item -> item.ticketTypeId() + ":" + (item.hasSeats()
                        ? item.seatIds().stream().map(UUID::toString).sorted().collect(Collectors.joining(","))
                        : "q" + item.quantity()))
                .collect(Collectors.joining("|"))
                + (request.salesChannel() == SalesChannel.WEB ? "" : "|channel=" + request.salesChannel());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private RequestHash() {}
}
