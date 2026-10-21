# ADR-020: Nhập sơ đồ ghế từ file CSV: kiểm tra hết rồi mới ghi, báo mọi dòng lỗi

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-21

## Bối cảnh
Lộ trình M4: "ban tổ chức import sơ đồ 50.000 ghế từ file, dòng nào lỗi thì báo đúng dòng đó". Trước M4, ghế chỉ thêm
được theo khối chữ nhật (`POST /organizer/events/{id}/seats`, tối đa 5.000 ghế mỗi lần): sân vận động với khu hình
quạt, hàng dài ngắn khác nhau phải bấm hàng trăm lần. Ban tổ chức thường đã có sơ đồ trong Excel.

## Các phương án
1. Ghi từng dòng, dòng lỗi thì bỏ qua và báo lại. Nhanh có kết quả, nhưng để lại sơ đồ dở dang: sửa file rồi tải lại
   thì các dòng đúng lần trước thành "trùng".
2. **Tất cả hoặc không gì cả**: đọc và kiểm tra hết, có lỗi thì không ghi gì, trả về mọi dòng lỗi một lần; không lỗi
   thì ghi tất cả trong một transaction.
3. Nhận file Excel (`.xlsx`) trực tiếp: tiện cho người dùng, nhưng cần thêm thư viện (Apache POI) và đọc định dạng
   phức tạp hơn nhiều.

## Quyết định
Chọn phương án 2, định dạng CSV (Excel lưu được bằng "CSV UTF-8"), không thêm thư viện.

- `POST /organizer/events/{id}/seats/import`, body là nội dung file (`Content-Type: text/csv`). Dòng tiêu đề
  `loai_ve,khu,hang,so_ghe`; viết có dấu, hoa thường đều nhận ("Loại vé" → `loai_ve`).
- Hai bước (`SeatImport`): (1) đọc file, kiểm tra từng dòng một mình, không cần database (đủ 4 cột, không trống, độ
  dài, số ghế 1–99999); (2) trong một transaction (`OrganizerCatalogService.importSeats`): khoá sự kiện, kiểm tra
  những gì cần database (loại vé có tồn tại và là vé ngồi, trùng với dòng nào trước đó trong file, ghế đã có trong
  sơ đồ), rồi INSERT. Các dòng hợp lệ ở bước 1 vẫn được kiểm tra ở bước 2 dù đã có lỗi, để báo đủ lỗi một lần.
- Báo lỗi theo số dòng người dùng thấy trong trình soạn thảo (dòng 1 là tiêu đề). `CsvReader` tự viết theo RFC 4180:
  ngoặc kép, `""`, xuống dòng trong ô, CRLF lẫn LF, bỏ BOM và dòng trống; đọc từng bản ghi, không tải cả file.
  Trả 422 `SEAT_IMPORT_INVALID` với 200 lỗi đầu (sắp theo dòng) và `errorCount` (tổng). Thông báo tiếng Việt cụ thể:
  "Trùng ghế với dòng 3", "Không có loại vé \"Hạng Z\" trong sự kiện"...
- Giới hạn: 5MB (đếm byte lúc đọc, vượt thì dừng ngay, 413), 100.000 ghế; file không phải UTF-8 báo rõ cách lưu lại.
  Chỉ khi sự kiện còn DRAFT (như thêm ghế theo khối). Kiểm tra quyền trước khi đọc file: BTC khác nhận 403.
- Ghi bằng JDBC batch, mỗi lần 1.000 dòng, với `reWriteBatchedInserts=true` của driver PostgreSQL: driver gộp lô thành
  vài câu `INSERT ... VALUES (...), (...)` nhiều dòng thay vì 1.000 lượt gửi. Ghế có id UUID sinh ở Java nên gửi theo
  lô được ngay. (Câu hỏi trong lộ trình: với `GenerationType.IDENTITY`, database mới cấp id lúc INSERT, mà Hibernate
  cần id ngay khi `persist` để quản lý entity, nên phải chạy từng câu INSERT một để lấy id về: Hibernate tắt batch
  INSERT cho entity đó.) Ràng buộc `uq_seats_position` vẫn là lớp chặn cuối cùng nếu hai lần nhập chen nhau.

## Hệ quả và đánh đổi
- Được: một lần sửa, một lần tải lại; sơ đồ không bao giờ dở dang.
- Mất: file 100.000 ghế nằm trong bộ nhớ dưới dạng danh sách dòng đã đọc (vài chục MB) trong lúc kiểm tra. Không nhận
  `.xlsx`; web có nút tải file mẫu.

## Bằng chứng
- `SeatImportIT` (acceptance, M4): 50.000 ghế, hai loại vé, nhập trong 736 ms (cả gửi HTTP); file có lỗi ở dòng 2 (số
  ghế không phải số), dòng 25.001 (trùng dòng 3) và dòng cuối 50.001 (loại vé lạ) → đúng ba lỗi đó, không ghế nào được
  thêm.
- `SeatImportApiIT`: BOM, CRLF, tiêu đề có dấu, ô có dấu phẩy trong ngoặc kép; 9 loại lỗi với thông báo đúng dòng; tải
  lại cùng file → "Ghế đã có trong sơ đồ"; 500 lỗi → 200 lỗi đầu và `errorCount = 500`; file 5,6MB → 413; không phải
  UTF-8 → 422 có gợi ý; sự kiện đã công bố → 409; BTC khác → 403 dù file rác; `image/png` → 415.
- `CsvTest`: đọc/ghi CSV, số dòng của bản ghi nhiều dòng, ngoặc chưa đóng, giới hạn kích thước.
