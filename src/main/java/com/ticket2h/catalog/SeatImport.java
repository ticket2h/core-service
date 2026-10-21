package com.ticket2h.catalog;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.ticket2h.common.csv.CsvReader;
import com.ticket2h.common.csv.CsvReader.CsvFormatException;
import com.ticket2h.common.csv.CsvReader.TooLargeException;
import com.ticket2h.common.error.BusinessException;
import com.ticket2h.common.error.ErrorCode;

/**
 * Nhập sơ đồ ghế từ file CSV (ADR-020). Hai bước:
 * <ol>
 * <li>Đọc file, kiểm tra từng dòng một mình (đủ cột, số ghế là số...). Không cần database, không mở transaction.</li>
 * <li>{@link OrganizerCatalogService#importSeats}: trong một transaction, kiểm tra những gì cần database (loại vé có
 * tồn tại, ghế đã có chưa) rồi INSERT tất cả theo lô.</li>
 * </ol>
 * Tất cả hoặc không gì cả: file có lỗi thì không ghế nào được thêm. Lỗi của cả hai bước được báo cùng lúc, sắp theo
 * dòng (tối đa {@value #MAX_REPORTED_ERRORS} lỗi đầu, kèm tổng số lỗi), để ban tổ chức sửa một lần rồi tải lại.
 * <p>
 * Định dạng: dòng tiêu đề {@code loai_ve,khu,hang,so_ghe} (viết có dấu, hoa thường đều được), mỗi dòng sau một ghế.
 */
@Service
public class SeatImport {

    static final List<String> HEADER = List.of("loai_ve", "khu", "hang", "so_ghe");
    static final int MAX_SEATS = 100_000;
    static final int MAX_REPORTED_ERRORS = 200;
    static final long MAX_BYTES = 5L * 1024 * 1024;

    private final OrganizerCatalogService catalog;
    private final CatalogQueries queries;

    SeatImport(OrganizerCatalogService catalog, CatalogQueries queries) {
        this.catalog = catalog;
        this.queries = queries;
    }

    /** BTC khác nhận 403 trước khi file được đọc. */
    public ImportResult importCsv(UUID organizerId, UUID eventId, InputStream body) {
        queries.ownedEvent(organizerId, eventId);
        Errors errors = new Errors();
        List<SeatLine> lines = new ArrayList<>();
        try {
            CsvReader reader = CsvReader.utf8(body, MAX_BYTES);
            CsvReader.Record header = reader.next();
            if (header == null) {
                errors.add(1, "File trống");
                throw errors.failure();
            }
            if (!HEADER.equals(header.fields().stream().map(SeatImport::headerName).toList())) {
                errors.add(header.line(), "Dòng tiêu đề phải là \"" + String.join(",", HEADER) + "\"");
                throw errors.failure();
            }
            for (CsvReader.Record record = reader.next(); record != null; record = reader.next()) {
                if (lines.size() + errors.count() >= MAX_SEATS) {
                    errors.add(record.line(), "File có quá " + String.format("%,d", MAX_SEATS).replace(',', '.')
                            + " ghế, hãy chia thành nhiều file");
                    throw errors.failure();
                }
                SeatLine line = parse(record, errors);
                if (line != null) {
                    lines.add(line);
                }
            }
        } catch (CsvFormatException e) {
            errors.add(e.line(), e.getMessage());
            throw errors.failure();
        } catch (TooLargeException e) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, e.getMessage());
        }
        if (lines.isEmpty() && errors.count() == 0) {
            errors.add(2, "File không có ghế nào");
        }
        // Kể cả khi đã có lỗi: các dòng hợp lệ vẫn được kiểm tra với database để báo đủ lỗi một lần
        return catalog.importSeats(organizerId, eventId, lines, errors);
    }

    /** "Loại vé" → "loai_ve", "SỐ GHẾ" → "so_ghe". */
    private static String headerName(String raw) {
        return VietnameseText.normalize(raw).replace(' ', '_');
    }

    private static SeatLine parse(CsvReader.Record record, Errors errors) {
        List<String> f = record.fields();
        int line = record.line();
        if (f.size() != HEADER.size()) {
            errors.add(line, "Cần " + HEADER.size() + " cột (" + String.join(",", HEADER) + "), dòng này có "
                    + f.size() + " cột");
            return null;
        }
        String type = f.get(0).strip();
        String section = f.get(1).strip();
        String row = f.get(2).strip();
        String number = f.get(3).strip();
        int before = errors.count();
        requireText(type, "loại vé", 100, line, errors);
        requireText(section, "khu", 50, line, errors);
        requireText(row, "hàng", 10, line, errors);
        Integer seatNumber = null;
        if (number.isEmpty()) {
            errors.add(line, "Thiếu số ghế");
        } else {
            try {
                seatNumber = Integer.valueOf(number);
            } catch (NumberFormatException e) {
                seatNumber = null;
            }
            if (seatNumber == null || seatNumber < 1 || seatNumber > 99_999) {
                errors.add(line, "Số ghế \"" + number + "\" phải là số nguyên từ 1 đến 99999");
            }
        }
        return errors.count() > before ? null : new SeatLine(line, type, section, row, seatNumber);
    }

    private static void requireText(String value, String name, int maxLength, int line, Errors errors) {
        if (value.isEmpty()) {
            errors.add(line, "Thiếu " + name);
        } else if (value.length() > maxLength) {
            errors.add(line, Character.toUpperCase(name.charAt(0)) + name.substring(1) + " dài quá " + maxLength
                    + " ký tự");
        }
    }

    /** Một dòng ghế đã qua kiểm tra riêng lẻ. {@code line}: số dòng trong file. */
    public record SeatLine(int line, String ticketType, String section, String row, int number) {}

    /** {@code byTicketType}: tên loại vé → số ghế đã thêm. */
    public record ImportResult(int created, Map<String, Integer> byTicketType) {}

    /** Gom lỗi theo dòng. Response chỉ có {@value #MAX_REPORTED_ERRORS} lỗi đầu (theo dòng), nhưng đếm hết. */
    static final class Errors {

        private final List<LineError> all = new ArrayList<>();

        void add(int line, String message) {
            all.add(new LineError(line, message));
        }

        int count() {
            return all.size();
        }

        SeatImportException failure() {
            List<Map<String, Object>> reported = all.stream()
                    .sorted(Comparator.comparingInt(LineError::line))   // sort ổn định: cùng dòng giữ thứ tự gặp
                    .limit(MAX_REPORTED_ERRORS)
                    .map(e -> Map.<String, Object>of("line", e.line(), "message", e.message()))
                    .toList();
            return new SeatImportException(reported, all.size());
        }

        private record LineError(int line, String message) {}
    }

    /** 422: trường {@code errors} (dòng, lý do) và {@code errorCount} trong ProblemDetail. */
    static final class SeatImportException extends BusinessException {

        SeatImportException(List<Map<String, Object>> errors, int count) {
            super(ErrorCode.SEAT_IMPORT_INVALID, "File có " + count + " dòng lỗi, chưa thêm ghế nào");
            with("errors", List.copyOf(errors));
            with("errorCount", count);
        }
    }
}
