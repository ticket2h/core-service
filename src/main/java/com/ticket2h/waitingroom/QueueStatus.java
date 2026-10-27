package com.ticket2h.waitingroom;

import java.time.Instant;

/**
 * Trạng thái của một người với phòng chờ của một sự kiện (ADR-027).
 *
 * @param state {@code NOT_REQUIRED}: sự kiện không có phòng chờ, đặt vé thẳng. {@code NOT_IN_QUEUE}: chưa xếp hàng
 *        (hoặc đã bị bỏ vì lâu không hỏi, hoặc vé vào cửa đã hết hạn): gọi {@code POST} để xếp. {@code WAITING}:
 *        đang chờ. {@code ADMITTED}: được vào, gửi {@code admissionToken} trong header {@code Admission-Token} khi
 *        đặt vé.
 * @param position thứ tự, 1 là người kế tiếp; chỉ có khi {@code WAITING}
 * @param waiting số người đang chờ
 * @param estimatedWaitSeconds ước lượng: thời gian tới giờ mở bán cộng {@code position / số người vào mỗi giây}
 * @param pollAfterSeconds hỏi lại sau ngần này giây (2–15, xa thì hỏi thưa): hỏi lại cũng là báo "vẫn đang chờ"
 */
public record QueueStatus(State state, Long position, Long waiting, Long estimatedWaitSeconds, int pollAfterSeconds,
        String admissionToken, Instant admissionExpiresAt) {

    public enum State { NOT_REQUIRED, NOT_IN_QUEUE, WAITING, ADMITTED }

    static QueueStatus notRequired() {
        return new QueueStatus(State.NOT_REQUIRED, null, null, null, 0, null, null);
    }
}
