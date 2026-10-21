package com.ticket2h.common.csv;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;

/**
 * Ghi CSV theo RFC 4180 (phân cách bằng dấu phẩy, xuống dòng CRLF), mở được bằng Excel. Ghi thẳng ra {@link Writer},
 * không giữ dòng nào trong bộ nhớ, nên xuất bao nhiêu dòng bộ nhớ cũng không tăng.
 * Ô bắt đầu bằng {@code = + - @}, tab hoặc CR được thêm dấu {@code '} ở đầu: Excel coi các ô đó là công thức, một
 * khách đặt tên "=HYPERLINK(...)" có thể chạy công thức trên máy ban tổ chức (CSV injection, OWASP).
 */
public final class CsvWriter {

    /** Byte order mark: Excel trên Windows cần nó để đọc file là UTF-8, không thì chữ có dấu bị vỡ. */
    public static final char BOM = '﻿';

    private final Writer out;

    public CsvWriter(Writer out) {
        this.out = out;
    }

    public CsvWriter bom() {
        write(String.valueOf(BOM));
        return this;
    }

    public CsvWriter row(String... cells) {
        return row(Arrays.asList(cells));   // List.of không nhận null
    }

    /** {@code null} ghi thành ô trống. */
    public CsvWriter row(List<String> cells) {
        StringBuilder line = new StringBuilder(128);
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            line.append(escape(cells.get(i)));
        }
        line.append("\r\n");
        write(line.toString());
        return this;
    }

    /** Một dòng chữ thường (không phải dữ liệu), ví dụ dòng báo lỗi cuối file. */
    public CsvWriter comment(String text) {
        write("# " + text.replace('\r', ' ').replace('\n', ' ') + "\r\n");
        return this;
    }

    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String escape(String cell) {
        if (cell == null || cell.isEmpty()) {
            return "";
        }
        String value = neutralizeFormula(cell);
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        return quote ? '"' + value.replace("\"", "\"\"") + '"' : value;
    }

    private static String neutralizeFormula(String value) {
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + value : value;
    }

    private void write(String text) {
        try {
            out.write(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
