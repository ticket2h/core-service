package com.ticket2h.dev.datagen;

import java.util.UUID;

/**
 * Id tất định của dữ liệu sinh (ADR-021 ở infra/docs/adr). identity-service sinh tài khoản và ban tổ chức, core-service
 * sinh sự kiện, đơn, vé; hai bên không đọc database của nhau nhưng vẫn khớp vì cùng tính id theo công thức dưới đây.
 * Bốn công thức đầu là hợp đồng với identity-service ({@code DatagenIds} bên đó phải cho cùng kết quả, xem
 * infra/docs/contracts.md, mục dữ liệu sinh). Cùng kiểu với id dữ liệu mẫu ({@code 0a...}, {@code 0b...}).
 */
public final class DatagenIds {

    public static UUID organizer(long i) {
        return fixed("0e", i);
    }

    /** Tài khoản ORGANIZER của ban tổ chức thứ {@code i}. */
    public static UUID organizerAccount(long i) {
        return fixed("0c", i);
    }

    public static UUID customer(long i) {
        return fixed("0d", i);
    }

    public static UUID staff(long i) {
        return fixed("0f", i);
    }

    /**
     * Id nội bộ của core (sự kiện, đơn, vé...): {@code tag} cho biết loại, {@code event} là số thứ tự sự kiện,
     * {@code n} là số thứ tự trong sự kiện. 64 bit thấp gói nguyên (tag, event, n) nên không bao giờ trùng. 64 bit cao
     * chỉ để "trông" cho hợp lý, và là một đánh đổi đã đo:
     * <ul>
     * <li>Web hiện 8 ký tự đầu của id làm mã đơn, mã vé, nên 8 ký tự đầu phải khác nhau giữa các đơn (như
     * {@code UUID.randomUUID()}).</li>
     * <li>Nhưng id hoàn toàn ngẫu nhiên làm mỗi INSERT rơi vào một chỗ ngẫu nhiên của index khoá chính; với bảng 10
     * triệu dòng index không nằm vừa bộ nhớ đệm, sinh dữ liệu chậm đi khoảng 4 lần (đo: 600 sự kiện mất 615 giây so với
     * 1.000 sự kiện 245 giây). Đây cũng là chi phí thật của UUID ngẫu nhiên trên bảng lớn (lý do có UUID version 7).</li>
     * </ul>
     * Nên 4 ký tự đầu cố định theo (loại, sự kiện), 4 ký tự sau ngẫu nhiên theo {@code n}: mã ngắn vẫn khác nhau,
     * còn INSERT của một sự kiện dồn vào một vùng nhỏ của index. Version 8 ("tự định nghĩa") theo RFC 9562.
     */
    static UUID of(int tag, long event, long n) {
        if (tag < 0 || tag > 0xFF || event < 0 || event >= 1L << 20 || n < 0 || n >= 1L << 34) {
            throw new IllegalArgumentException("Ngoài phạm vi id sinh: tag=" + tag + " event=" + event + " n=" + n);
        }
        long lsb = 0x8000_0000_0000_0000L | ((long) tag << 54) | (event << 34) | n;   // 2 bit đầu là variant 10
        long eventPart = mix(((long) tag << 20) | event) & 0xFFFF;
        long rowPart = mix(lsb) & 0xFFFF;
        long msb = (eventPart << 48) | (rowPart << 32) | (mix(~lsb) & 0xFFFF_0FFFL) | 0x8000L;
        return new UUID(msb, lsb);
    }

    /** Hàm trộn của SplitMix64. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static UUID fixed(String prefix, long i) {
        return UUID.fromString(prefix + "000000-0000-4000-8000-%012x".formatted(i));
    }

    private DatagenIds() {}
}
