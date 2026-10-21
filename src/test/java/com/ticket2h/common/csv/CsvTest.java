package com.ticket2h.common.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class CsvTest {

    @Test
    void reader_handles_bom_crlf_quotes_blank_lines_and_reports_record_start_lines() {
        String csv = "﻿a,b,c\r\n"
                + "\r\n"
                + "\"Khu A, tầng 2\",\"Nói \"\"xin chào\"\"\",3\r\n"
                + "\"hai\ndòng\",x,\n"
                + "cuối,,";
        List<CsvReader.Record> records = readAll(csv);

        assertThat(records).extracting(CsvReader.Record::line).containsExactly(1, 3, 4, 6);
        assertThat(records.get(0).fields()).containsExactly("a", "b", "c");
        assertThat(records.get(1).fields()).containsExactly("Khu A, tầng 2", "Nói \"xin chào\"", "3");
        assertThat(records.get(2).fields()).containsExactly("hai\ndòng", "x", "");
        assertThat(records.get(3).fields()).containsExactly("cuối", "", "");
    }

    @Test
    void reader_rejects_unclosed_quotes_and_text_after_a_closing_quote_with_the_line() {
        assertThatThrownBy(() -> readAll("a\n\"mở mà không đóng,b\n"))
                .isInstanceOfSatisfying(CsvReader.CsvFormatException.class, e -> assertThat(e.line()).isEqualTo(2));
        assertThatThrownBy(() -> readAll("a\nb\n\"x\"y,z\n"))
                .isInstanceOfSatisfying(CsvReader.CsvFormatException.class, e -> assertThat(e.line()).isEqualTo(3));
    }

    @Test
    void utf8_reader_rejects_other_encodings_and_oversized_files() {
        byte[] windows1258 = {'a', ',', (byte) 0xC0, '\n'};   // không phải UTF-8 hợp lệ
        assertThatThrownBy(() -> CsvReader.utf8(new ByteArrayInputStream(windows1258), 1_000).next())
                .isInstanceOf(CsvReader.CsvFormatException.class).hasMessageContaining("UTF-8");

        byte[] big = "x,y\n".repeat(1_000).getBytes(StandardCharsets.UTF_8);
        CsvReader reader = CsvReader.utf8(new ByteArrayInputStream(big), 100);
        assertThatThrownBy(() -> {
            while (reader.next() != null) {
                // đọc tới khi vượt giới hạn
            }
        }).isInstanceOf(CsvReader.TooLargeException.class);
    }

    @Test
    void writer_quotes_when_needed_and_neutralizes_formulas() {
        StringWriter out = new StringWriter();
        new CsvWriter(out).bom()
                .row("thường", "có, phẩy", "có \"ngoặc\"", null, "=1+1", "+84 912", "-5", "@SUM(A1)", "a\nb")
                .flush();

        assertThat(out.toString()).isEqualTo("﻿thường,\"có, phẩy\",\"có \"\"ngoặc\"\"\",,'=1+1,'+84 912,'-5,"
                + "'@SUM(A1),\"a\nb\"\r\n");
    }

    @Test
    void what_the_writer_writes_the_reader_reads_back() {
        List<String> cells = List.of("Trần, Thị \"Bé\"", "", "xuống\r\ndòng", "Ánh");
        StringWriter out = new StringWriter();
        new CsvWriter(out).bom().row(cells).flush();

        assertThat(readAll(out.toString()).getFirst().fields()).isEqualTo(cells);
    }

    private static List<CsvReader.Record> readAll(String csv) {
        CsvReader reader = new CsvReader(new StringReader(csv));
        List<CsvReader.Record> records = new ArrayList<>();
        for (CsvReader.Record r = reader.next(); r != null; r = reader.next()) {
            records.add(r);
        }
        return records;
    }
}
