package com.ticket2h.common.csv;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Đọc CSV theo RFC 4180: phân cách bằng dấu phẩy, ô có dấu phẩy/xuống dòng/dấu {@code "} thì nằm trong ngoặc kép,
 * {@code ""} trong ngoặc kép là một dấu {@code "}. Nhận cả CRLF (Excel trên Windows) lẫn LF. Bỏ BOM ở đầu file và
 * dòng trống. Đọc từng bản ghi một, không tải cả file vào bộ nhớ.
 * Mỗi bản ghi kèm số dòng (dòng đầu tiên của bản ghi trong file, đếm từ 1), để báo lỗi đúng dòng người dùng thấy
 * trong Excel hay trình soạn thảo.
 */
public final class CsvReader {

    private final Reader in;
    private int line = 1;
    private int pending = -2;   // ký tự đọc trước một bước; -2: chưa có
    private boolean started;

    public CsvReader(Reader in) {
        this.in = in;
    }

    /**
     * Đọc file UTF-8 tối đa {@code maxBytes} byte. Byte không hợp lệ theo UTF-8 (file lưu bằng bảng mã khác) báo
     * {@link CsvFormatException}; quá {@code maxBytes} báo {@link TooLargeException}.
     */
    public static CsvReader utf8(InputStream in, long maxBytes) {
        Reader reader = new InputStreamReader(new LimitedInputStream(in, maxBytes), StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
        return new CsvReader(reader);
    }

    /** Bản ghi tiếp theo, {@code null} khi hết file. */
    public Record next() {
        int c = read();
        if (!started) {
            started = true;
            if (c == '﻿') {
                c = read();
            }
        }
        while (c == '\r' || c == '\n') {   // dòng trống
            c = skipLineBreak(c);
        }
        if (c == -1) {
            return null;
        }
        int recordLine = line;
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean afterQuote = false;
        while (true) {
            if (quoted) {
                if (c == -1) {
                    throw new CsvFormatException(recordLine, "Thiếu dấu \" đóng ngoặc");
                }
                if (c == '"') {
                    int next = read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        afterQuote = true;
                        c = next;
                        continue;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append((char) c);
                }
                c = read();
                continue;
            }
            if (c == ',' ) {
                fields.add(field.toString());
                field.setLength(0);
                afterQuote = false;
            } else if (c == '\r' || c == '\n' || c == -1) {
                fields.add(field.toString());
                if (c != -1) {
                    unread(skipLineBreak(c));
                }
                return new Record(recordLine, fields);
            } else if (c == '"' && field.isEmpty() && !afterQuote) {
                quoted = true;
            } else if (afterQuote) {
                throw new CsvFormatException(recordLine, "Có ký tự sau dấu \" đóng ngoặc");
            } else {
                field.append((char) c);
            }
            c = read();
        }
    }

    /** Đọc hết một dấu xuống dòng (CRLF, LF hoặc CR), tăng số dòng, trả ký tự kế tiếp. */
    private int skipLineBreak(int c) {
        line++;
        int next = read();
        if (c == '\r' && next == '\n') {
            next = read();
        }
        return next;
    }

    private int read() {
        if (pending != -2) {
            int c = pending;
            pending = -2;
            return c;
        }
        try {
            return in.read();
        } catch (CharacterCodingException e) {
            throw new CsvFormatException(line, "File phải lưu dạng UTF-8 (trong Excel: Lưu thành CSV UTF-8)");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void unread(int c) {
        pending = c;
    }

    /** {@code line}: dòng bắt đầu của bản ghi. */
    public record Record(int line, List<String> fields) {}

    /** File sai cấu trúc CSV hoặc sai bảng mã, kèm dòng gặp lỗi. */
    public static final class CsvFormatException extends RuntimeException {

        private final int line;

        CsvFormatException(int line, String message) {
            super(message);
            this.line = line;
        }

        public int line() {
            return line;
        }
    }

    public static final class TooLargeException extends RuntimeException {

        TooLargeException(long maxBytes) {
            super("File lớn hơn " + maxBytes / (1024 * 1024) + "MB");
        }
    }

    /** Đếm byte đã đọc, quá giới hạn thì dừng ngay, không đọc tiếp phần còn lại của request. */
    private static final class LimitedInputStream extends FilterInputStream {

        private final long maxBytes;
        private long count;

        LimitedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) {
            count += n;
            if (count > maxBytes) {
                throw new TooLargeException(maxBytes);
            }
        }
    }
}
