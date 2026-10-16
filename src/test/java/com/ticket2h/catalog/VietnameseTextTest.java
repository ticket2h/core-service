package com.ticket2h.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class VietnameseTextTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Sơn Tùng M-TP Live      | son tung m tp live",
            "ĐÀ NẴNG                 | da nang",
            "Hồ Chí Minh             | ho chi minh",
            "Nguyễn Ánh 9 & Mỹ Tâm   | nguyen anh 9 my tam",
            "Thủ Đức, Q.9            | thu duc q 9",
            "Ưng Hoàng Phúc          | ung hoang phuc"})
    void strips_diacritics_and_punctuation(String input, String expected) {
        assertThat(VietnameseText.normalize(input)).isEqualTo(expected);
    }

    @Test
    void decomposed_unicode_input_gives_the_same_result() {
        String composed = "Sơn Tùng";
        String decomposed = java.text.Normalizer.normalize(composed, java.text.Normalizer.Form.NFD);

        assertThat(VietnameseText.normalize(decomposed)).isEqualTo(VietnameseText.normalize(composed));
    }

    @Test
    void query_tokens_are_safe_for_like() {
        assertThat(VietnameseText.tokens("  Sơn   TÙNG %_ ")).containsExactly("son", "tung");
        assertThat(VietnameseText.tokens(null)).isEmpty();
    }
}
