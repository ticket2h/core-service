# ADR-018: Phân trang keyset cho danh sách dài

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-21
- Áp dụng: `GET /organizer/events/{id}/orders` (core-service), `GET /admin/users` (identity-service), các lô của file
  danh sách khách (ADR-017)

## Bối cảnh
Lộ trình M4: "mở trang thứ 10.000 của danh sách vẫn nhanh". Danh sách tài khoản ở trang quản trị có 2 triệu dòng,
danh sách đơn của sự kiện lớn có 200.000 dòng. Trước M4 `GET /admin/users` dùng `LIMIT/OFFSET` của Spring Data, giới
hạn `page ≤ 10.000`, kèm một câu `count(*)` mỗi trang.

`OFFSET n` buộc PostgreSQL đọc rồi bỏ đi n dòng đầu: trang 10.000 cỡ 20 dòng là đọc 200.000 dòng để trả 20. Thêm vào
đó, có người tạo tài khoản mới trong lúc admin đang xem thì mọi dòng bị đẩy xuống một bậc: trang sau lặp lại một
dòng của trang trước.

## Các phương án
1. Giữ OFFSET, tối ưu bằng "deferred join" (`WHERE id IN (SELECT id ... OFFSET n LIMIT 20)` để phần bỏ qua chỉ quét
   index hẹp). Nhanh hơn vài lần nhưng vẫn tăng theo độ sâu, vẫn lặp/sót khi dữ liệu thay đổi.
2. **Keyset (seek method)**: trang sau gửi lại vị trí của dòng cuối trang trước, câu SQL đi tiếp từ đó:
   `WHERE (created_at, id) < (:afterAt, :afterId) ORDER BY created_at DESC, id DESC LIMIT 21`. Với index đúng thứ tự
   `(…, created_at DESC, id DESC)`, PostgreSQL nhảy thẳng tới vị trí đó trong index (điều kiện so sánh cả hàng nằm
   trong `Index Cond`) và đọc đúng 21 dòng, ở trang nào cũng vậy.
3. Cursor phía database (`DECLARE CURSOR`) giữ mở giữa các request: cần giữ kết nối và transaction cho từng người
   xem, không hợp với API stateless.

## Quyết định
Chọn phương án 2.

- Thứ tự luôn có `id` ở cuối để duy nhất: nhiều dòng cùng `created_at` (cùng micro giây) vẫn phân trang không lặp,
  không sót. Index: `orders (event_id, created_at DESC, id DESC)` (V116 của core), `users (created_at DESC, id DESC)`
  và `users (role, created_at DESC, id DESC)` (V3 của identity).
- Cursor gửi cho client là chuỗi mờ base64url của `thời gian|id` (`KeysetCursor` ở core, `UserCursor` ở identity),
  client gửi lại nguyên văn. Không phải bí mật: sửa tay chỉ nhảy tới chỗ khác trong danh sách mà người đó vốn được
  xem (quyền vẫn kiểm tra theo sự kiện). Chuỗi hỏng → 400.
- Lấy thừa một dòng (`limit + 1`) để biết còn trang sau, thay cho `count(*)`. Response `{items, limit, nextCursor}`;
  `nextCursor: null` là hết. Không có tổng số.
- Câu SQL ghép từ các đoạn viết sẵn tuỳ bộ lọc (trạng thái, vai trò, chuỗi tìm kiếm). Không dùng mẹo
  `(:status IS NULL OR status = :status)`: với prepared statement PostgreSQL có thể chọn kế hoạch chung không dùng
  được index.
- Tìm kiếm chuỗi con trong email/họ tên ở identity (`LIKE '%…%'`) dùng index trigram `pg_trgm` (từ 3 ký tự), vì
  B-tree không giúp được `LIKE` có `%` ở đầu.
- Web đổi phân trang số trang thành nút "Xem thêm" (TanStack Query `useInfiniteQuery`).

## Hệ quả và đánh đổi
- Được: thời gian mỗi trang không phụ thuộc độ sâu; dữ liệu thêm trong lúc xem không làm lặp hay sót.
- Mất: không nhảy thẳng tới "trang 9.437" được, không có tổng số trang. Đổi thứ tự sắp xếp thì cần cursor và index
  khác. Danh sách công khai `GET /events` vẫn dùng `page/size`: số sự kiện đang bán nhỏ, và kết quả tìm kiếm không
  dài tới mức OFFSET thành vấn đề. `/me/orders` vẫn trả 50 đơn gần nhất.

## Bằng chứng
- `OrderPagingIT` (acceptance, M4): sự kiện 250.000 đơn (cạnh một sự kiện 100.000 đơn), trang 10.000: theo
  `EXPLAIN ANALYZE` keyset chỉ đọc 21 dòng của index, OFFSET đọc hơn 200.000 dòng; API trả trang 10.000 trong khoảng
  11 ms (trung vị 5 lần) và đúng đơn thứ 199.981.
- `OrganizerOrderListApiIT`: 45 đơn cùng `created_at` đi hết 3 trang không lặp, không sót, đúng thứ tự database.
- `AdminListApiIT` (identity): đi hết danh sách theo `nextCursor`; tài khoản mới tạo giữa hai trang không làm trang
  sau lặp dòng (OFFSET thì lặp); cursor hỏng → 400.

## Đo trên dữ liệu sinh

Dữ liệu sinh bằng `infra/scripts/datagen.sh` (ADR-021) trên máy dev (20 nhân, 30GB RAM, NVMe, PostgreSQL 17 cấu hình
mặc định của image, sau `VACUUM ANALYZE`): 2 triệu tài khoản, 2.001 sự kiện, 10.000.000 đơn, 12,8 triệu dòng
`order_items`, 9 triệu thanh toán, 18,5 triệu vé, 6,1 triệu ghế, 30 triệu lượt quét; database `core` 24GB. Sự kiện lớn
"Đại nhạc hội Ticket2h 2026": 200.000 đơn (180.242 đã trả tiền), 368.662 vé.

| Danh sách | Cách | Thời gian (`EXPLAIN ANALYZE`) | Dòng đọc |
|---|---|---|---|
| 200.000 đơn của sự kiện lớn, trang 10.000 | `OFFSET 199980` | 15 ms | 200.000 dòng index (index-only scan) |
| | keyset | 0,02 ms | 20 |
| 2 triệu tài khoản, trang 10.000 | `OFFSET 199980` (API cũ) | 257 ms, cộng `count(*)` 69 ms mỗi trang | 200.000 dòng (index scan, đọc cả bảng) |
| 2 triệu tài khoản, trang 50.000 | `OFFSET 999980` | 795 ms | 1.000.000 |
| | keyset ở cùng độ sâu | 0,15 ms | 21 |
| Tìm "khach1234567" trong 2 triệu tài khoản | có index trigram | 19 ms | bitmap từ index |
| | không có index trigram | 1.423 ms | quét index thời gian, lọc từng dòng |

Qua API: trang 10.000 của danh sách đơn 16–22 ms (gồm hỏi identity tên 20 người mua), trang 10.000 của danh sách tài
khoản 6–12 ms. Trên web, mỗi lần "Xem thêm" ở trang quản trị khoảng 270–280 ms (gồm vẽ lại bảng). OFFSET trên danh
sách đơn của một sự kiện vẫn chịu được ở độ sâu này nhờ index-only scan, nhưng tăng tuyến tính; danh sách tài khoản
phải đọc cả dòng nên đã chậm thấy rõ.
