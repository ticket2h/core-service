package com.ticket2h.dev.datagen;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;

/**
 * Nạp dữ liệu bằng {@code COPY ... FROM STDIN}: PostgreSQL nhận một luồng CSV và ghi thẳng vào bảng, nhanh hơn
 * INSERT (kể cả INSERT theo lô) nhiều lần vì không phải phân tích từng câu lệnh. Đổi lại: cú pháp riêng của
 * PostgreSQL, phải dùng API của driver ({@code PGConnection}), và lỗi ở một dòng làm hỏng cả lần COPY.
 * Dòng được đệm khoảng 64KB rồi mới gửi, không giữ cả bảng trong bộ nhớ.
 */
final class CopyWriter implements AutoCloseable {

    private static final int FLUSH_AT = 64 * 1024;

    private final CopyIn copy;
    private final StringBuilder buffer = new StringBuilder(FLUSH_AT + 4096);
    private long rows;

    CopyWriter(Connection connection, String table, String columns) throws SQLException {
        this.copy = connection.unwrap(PGConnection.class).getCopyAPI()
                .copyIn("COPY " + table + " (" + columns + ") FROM STDIN WITH (FORMAT csv)");
    }

    /** {@code null} là NULL; chuỗi được đặt trong ngoặc kép khi cần. */
    void row(Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                buffer.append(',');
            }
            Object value = values[i];
            if (value == null) {
                continue;   // ô trống không ngoặc kép: NULL
            }
            String text = value instanceof BigDecimal d ? d.toPlainString() : value.toString();
            if (text.isEmpty() || text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0) {
                buffer.append('"').append(text.replace("\"", "\"\"")).append('"');
            } else {
                buffer.append(text);
            }
        }
        buffer.append('\n');
        rows++;
        if (buffer.length() >= FLUSH_AT) {
            flush();
        }
    }

    long rows() {
        return rows;
    }

    private void flush() throws SQLException {
        byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
        copy.writeToCopy(bytes, 0, bytes.length);
        buffer.setLength(0);
    }

    @Override
    public void close() throws SQLException {
        flush();
        copy.endCopy();
    }
}
