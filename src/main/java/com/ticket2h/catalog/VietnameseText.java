package com.ticket2h.catalog;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Chuẩn hoá chữ tiếng Việt để tìm kiếm không dấu: "Sơn Tùng M-TP" → "son tung m tp".
 * Cột {@code events.search_text} lưu kết quả của hàm này; câu tìm kiếm cũng đi qua cùng hàm.
 */
public final class VietnameseText {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final int MAX_QUERY_TOKENS = 6;

    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        // "đ" không phải chữ "d" + dấu nên NFD không tách được; đổi tay. "Đ" thành "đ" khi viết thường.
        String lower = text.toLowerCase(Locale.ROOT).replace('đ', 'd');
        String withoutMarks = COMBINING_MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
        return NON_ALPHANUMERIC.matcher(withoutMarks).replaceAll(" ").trim();
    }

    /** Các từ của câu tìm kiếm, đã chuẩn hoá. Kết quả chỉ chứa [a-z0-9] nên dùng an toàn trong LIKE. */
    public static List<String> tokens(String query) {
        String normalized = normalize(query);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(normalized.split(" ")).distinct().limit(MAX_QUERY_TOKENS).toList();
    }

    private VietnameseText() {}
}
