# ADR-017: Xuất file CSV lớn: đọc theo lô keyset khi cần gọi service khác, đọc theo luồng khi không

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-21
- Liên quan: ADR-013 (tài khoản ở identity-service), ADR-018 (keyset), ADR-019 (partition `checkin_logs`)

## Bối cảnh
Lộ trình M4: "xuất file CSV 500.000 khách khi app chỉ có 256MB heap mà không bị tràn bộ nhớ". 500.000 dòng, mỗi dòng
vài trăm byte: gom hết vào một `List` hay một chuỗi rồi mới trả là hàng trăm MB, vượt 256MB.

Thêm một điều kiện riêng của hệ thống: core không lưu tên và email (ADR-013), phải hỏi identity-service. Và quy ước
của repo: không gọi HTTP trong transaction (giữ kết nối database và khoá trong lúc chờ mạng).

Hai file cần xuất: danh sách khách (vé + tên + email người giữ vé) và lịch sử soát vé (lượt quét + tên nhân viên).

## Các phương án
1. Đọc hết vào bộ nhớ rồi ghi (`List<Row>`). Đơn giản; chết với 500.000 dòng ở 256MB.
2. **Đọc theo luồng bằng cursor của database** (`fetchSize`): driver PostgreSQL lấy về từng 1.000 dòng một, nhưng
   chỉ khi autocommit tắt, tức là phải nằm trong transaction từ dòng đầu tới dòng cuối. Nếu giữa chừng phải gọi
   identity thì vi phạm "không gọi HTTP trong transaction".
3. **Đọc theo lô keyset**: mỗi lô 1.000 dòng là một câu `SELECT ... WHERE (issued_at, id) > (dòng cuối lô trước)
   ORDER BY issued_at, id LIMIT 1000` tự commit; giữa hai lô gọi identity một lần cho cả lô
   (`POST /internal/users/batch`), ghi lô ra response, rồi mới đọc lô sau.
4. `StreamingResponseBody` / `ResponseBodyEmitter` của Spring: ghi ở luồng khác, chịu timeout của async request
   (mặc định 30 giây), file lớn bị cắt.

## Quyết định
- **Danh sách khách: phương án 3** (`AttendeeExport`). Trong bộ nhớ chỉ có một lô. Không transaction nào mở trong
  lúc gọi identity. 500.000 khách là 500 lần gọi identity, không phải 500.000. Đánh đổi: file không phải ảnh chụp tại
  một thời điểm (vé chuyển nhượng giữa lúc xuất có thể mang tên chủ mới), chấp nhận được với danh sách khách.
- **Lịch sử soát vé: phương án 2** (`CheckinLogStore.stream` trong `CheckinLogReader`, `@Transactional(readOnly =
  true)`, fetch size 1.000). Tên nhân viên hỏi identity MỘT lần TRƯỚC khi mở transaction (nhân viên chỉ vài chục
  người, lấy danh sách id bằng `SELECT DISTINCT`), nên phần đọc không gọi mạng. Đánh đổi: client tải chậm thì
  transaction mở lâu theo; giới hạn khoảng ngày tối đa 31 ngày.
  Hai file dùng hai cách là có chủ ý: để thấy khi nào cách nào đúng.
- Ghi thẳng vào output stream của response trong luồng xử lý request (không phương án 4). Header (`Content-Type`,
  `Content-Disposition`, `Cache-Control: no-store` vì có email) chỉ đặt sau khi đã kiểm tra quyền và chuẩn bị xong lô
  đầu: BTC khác vẫn nhận 403, identity sập ngay từ đầu vẫn nhận 502 JSON như mọi API khác.
- Lỗi sau khi đã ghi dòng đầu: không đổi được mã HTTP nữa. Ghi một dòng `# LỖI: file chưa đầy đủ...` ở cuối file để
  người mở biết; identity chập chờn thì thử lại một lần (0,5 giây) trước khi bỏ cuộc.
- CSV: UTF-8 có BOM (Excel trên Windows mới đọc đúng chữ có dấu), CRLF, ngoặc kép khi cần. Ô bắt đầu bằng `= + - @`,
  tab, CR được thêm `'` ở đầu (`CsvWriter`): chống CSV injection, khách đặt tên `=HYPERLINK(...)` không chạy được
  công thức trên máy ban tổ chức.

## Hệ quả và đánh đổi
- Được: bộ nhớ không tăng theo số dòng; quy ước "không gọi HTTP trong transaction" vẫn giữ.
- Mất: lô keyset phụ thuộc index `tickets (event_id, issued_at, id)` (V116); file khách không nhất quán tuyệt đối theo
  thời điểm. Web tải file bằng `fetch` (cần access token) rồi mới lưu, nên trình duyệt giữ cả file trong bộ nhớ (vài
  chục MB với 500.000 dòng). Muốn tải thẳng xuống đĩa thì cần link tải có chữ ký ngắn hạn (chưa làm).

## Bằng chứng
- `ExportMemoryIT` (acceptance, M4) chạy trong JVM riêng `-Xmx256m` (execution `export-memory` của failsafe), app và
  test cùng nằm trong 256MB đó: 500.000 khách xuất đủ 500.000 dòng, 500 lần gọi identity, 28,6 giây; 500.000 lượt quét
  xuất trong 1 giây.
- `OrganizerExportIT`: nội dung từng cột, giờ vào cổng theo giờ Việt Nam, ngoặc kép và chặn công thức, tài khoản đã
  xoá, 2.500 vé = 3 lô = 3 lần gọi identity, identity sập trước dòng đầu → 502 JSON, chập chờn một lần → vẫn đủ file,
  BTC khác → 403, khoảng ngày quá 31 ngày → 400.

## Đo trên 10 triệu đơn

Dữ liệu sinh bằng `infra/scripts/datagen.sh` (ADR-021) trên máy dev (20 nhân, 30GB RAM, NVMe, PostgreSQL 17 cấu hình
mặc định của image, sau `VACUUM ANALYZE`): 2 triệu tài khoản, 2.001 sự kiện, 10.000.000 đơn, 12,8 triệu dòng
`order_items`, 9 triệu thanh toán, 18,5 triệu vé, 6,1 triệu ghế, 30 triệu lượt quét; database `core` 24GB. Sự kiện lớn
"Đại nhạc hội Ticket2h 2026": 200.000 đơn (180.242 đã trả tiền), 368.662 vé.

| File | Dòng | Kích thước | Thời gian |
|---|---|---|---|
| Danh sách khách sự kiện lớn, core trong container (heap mặc định) | 368.662 | 49MB | 6,3 giây |
| Như trên, core chạy với `-Xmx256m` | 368.662 | 49MB | 6,2 giây; heap đã cấp 96MB, đang dùng 64MB sau khi xuất |
| Như trên, qua gateway nginx có gzip (từ 2026-10-21) | 368.662 | 8,4MB truyền đi | 4,8 giây |
| Lịch sử soát vé 3 ngày của một sự kiện đã diễn, `-Xmx256m` | 24.266 | 3,1MB | 0,14 giây |

Trên web (nút "Xuất file" → "Danh sách khách"): 5,8–6,1 giây từ lúc bấm tới lúc file được lưu.
Với identity thật, mỗi lô 1.000 vé tốn khoảng 17 ms; trong test (`FakeIdentity` là HTTP server của JDK) chậm hơn
nhiều (500.000 dòng 28,6 giây), nên con số của test không dùng để nói về tốc độ.
