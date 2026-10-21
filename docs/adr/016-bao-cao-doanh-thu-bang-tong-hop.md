# ADR-016: Báo cáo doanh thu bằng bảng tổng hợp, cộng dồn qua hàng đợi

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-21
- Liên quan: ADR-003 (điểm nghẽn một dòng), ADR-007 (outbox), M4 của lộ trình

## Bối cảnh
Ban tổ chức cần doanh thu theo ngày, loại vé, kênh bán, "trên hàng triệu dòng, trả kết quả dưới 1 giây". Sau 2 năm
bảng `orders` có 10 triệu dòng, `order_items` hơn 10 triệu. Sự kiện lớn có 200.000 đơn. Trước M4, `orders` không có
index nào bắt đầu bằng `event_id`, và đơn không ghi kênh bán.

Ngày bán phải theo giờ của sự kiện: đơn trả tiền lúc 23:30 giờ Việt Nam là ngày hôm đó, dù theo giờ UTC của server đã
sang ngày hôm sau (bẫy múi giờ trong lộ trình).

## Các phương án
1. **Tính thẳng mỗi lần xem**: `GROUP BY` trên `orders JOIN order_items` của sự kiện, có index `(event_id, …)`.
   Đơn giản, luôn đúng tuyệt đối. Nhưng thời gian tăng theo số đơn của sự kiện, và đọc rải rác hàng trăm nghìn dòng
   khắp bảng 10 triệu dòng.
2. **Materialized view** `REFRESH MATERIALIZED VIEW CONCURRENTLY` theo lịch. Không phải viết code cộng dồn, nhưng mỗi
   lần làm mới là tính lại cả 10 triệu đơn, và số liệu cũ tới tận lần làm mới sau.
3. **Cộng thẳng vào bảng tổng hợp ngay lúc trả tiền**, trong transaction của webhook. Luôn mới nhất, nhưng mọi đơn
   của một sự kiện trong cùng ngày, cùng kênh rơi vào vài dòng tổng hợp: lúc mở bán, mọi webhook xếp hàng chờ khoá
   vài dòng đó, đúng điểm nghẽn một dòng của ADR-003, ngay trên đường thanh toán.
4. **Hàng đợi + job cộng dồn**: lúc đơn chuyển PAID, ghi `order_id` vào bảng `sales_rollup_queue` trong cùng
   transaction (mỗi đơn một dòng riêng, không tranh khoá). Job vài giây một lần lấy một lô, cộng vào bảng tổng hợp.

## Quyết định
Chọn phương án 4.

- Bảng tổng hợp: `sales_daily_items (event_id, sale_date, sales_channel, ticket_type_id) → tickets, revenue` và
  `sales_daily_orders (event_id, sale_date, sales_channel) → orders`. Tách hai bảng vì một đơn mua nhiều loại vé:
  cộng số đơn qua các loại vé sẽ đếm một đơn nhiều lần. Ngày và kênh thì mỗi đơn chỉ có một.
  `sale_date = (paid_at AT TIME ZONE events.time_zone)::date`.
- **Đúng một lần**: job lấy, xoá khỏi hàng đợi và cộng vào bảng tổng hợp trong MỘT câu lệnh (CTE có `DELETE ...
  RETURNING` rồi hai `INSERT ... ON CONFLICT DO UPDATE`, `SalesRollupStore.APPLY_BATCH`). Câu lệnh là nguyên tử: lỗi
  thì cả lô còn nguyên trong hàng đợi, thành công thì mỗi đơn đã cộng và đã bị xoá. Không cần bảng "đơn đã cộng" để
  chống trùng như khi dùng Kafka (giao ít nhất một lần).
- **Không sót**: đơn vào hàng đợi trong cùng transaction với lúc chuyển PAID (`PaymentWebhookProcessor`), giống
  transactional outbox. Webhook gửi 3 lần chỉ chuyển PAID một lần, nên chỉ một dòng hàng đợi (thêm
  `ON CONFLICT DO NOTHING` cho chắc).
- Hai instance: `FOR UPDATE SKIP LOCKED` cho mỗi instance lấy lô khác nhau; `ORDER BY` khoá chính trước khi INSERT
  để các dòng tổng hợp luôn bị khoá theo cùng thứ tự (không deadlock).
- `SalesRollup.rebuild(eventId)` / `rebuildAll()` tính lại từ bảng gốc (sau khi nạp dữ liệu thẳng vào database, hoặc
  khi nghi ngờ lệch). Khoá advisory chung với job, để job không cộng chồng lên trong lúc tính lại.
- Kênh bán `orders.sales_channel` (`WEB`, `MOBILE_APP`) do client khai báo trong `POST /orders`, mặc định `WEB`.
  Thêm cột có `DEFAULT` hằng số không phải ghi lại bảng (PostgreSQL 11+): migration chạy tức thì trên 10 triệu đơn.
  `RequestHash` chỉ thêm kênh vào chuỗi băm khi khác `WEB`, nên đơn cũ giữ nguyên hash.
- Cột sắp xếp do người dùng chọn (`sort`, `direction`, `groupBy`) nhận bằng enum, mỗi giá trị dịch sang một đoạn SQL
  viết sẵn (`SalesReportStore.orderBy`). Chuỗi lạ bị Spring từ chối (400) trước khi tới SQL.

## Hệ quả và đánh đổi
- Được: báo cáo đọc vài trăm dòng tổng hợp thay vì hàng trăm nghìn dòng đơn; đường thanh toán chỉ thêm một INSERT
  vào bảng không ai tranh.
- Mất: báo cáo chậm hơn thực tế tối đa ~5 giây (chu kỳ job), web ghi rõ điều này. Thêm một job, hai bảng và một đường
  "tính lại" phải giữ cho khớp với cách cộng dồn (`rebuilding_from_orders_gives_the_same_numbers...` kiểm tra).
- Mọi đường mới chuyển đơn sang PAID phải nhớ gọi `SalesRollup.enqueue` (ghi trong CLAUDE.md). Hoàn tiền sau khi đã
  PAID chưa có; khi có, hàng đợi cần thêm dấu (+/−).
- Không có số đơn khi gom theo loại vé (trả `null`) thay vì một con số sai.

## Bằng chứng
- `SalesReportPerformanceIT` (acceptance, M4): sự kiện 200.000 đơn trong bảng 300.000 đơn, báo cáo DAY,
  TICKET_TYPE, CHANNEL chậm nhất 4–5 ms qua HTTP; tính thẳng từ `orders` mất 164 ms trên cùng dữ liệu.
- `SalesReportApiIT`: số liệu đúng theo ngày/loại vé/kênh; 23:30 và 00:30 giờ Việt Nam thành hai ngày; webhook gửi
  3 lần và job chạy nhiều lần vẫn cộng một lần; tính lại ra đúng số của job; `sort=revenue; DROP TABLE orders` → 400.

## Đo trên 10 triệu đơn

Dữ liệu sinh bằng `infra/scripts/datagen.sh` (ADR-021) trên máy dev (20 nhân, 30GB RAM, NVMe, PostgreSQL 17 cấu hình
mặc định của image, sau `VACUUM ANALYZE`): 2 triệu tài khoản, 2.001 sự kiện, 10.000.000 đơn, 12,8 triệu dòng
`order_items`, 9 triệu thanh toán, 18,5 triệu vé, 6,1 triệu ghế, 30 triệu lượt quét; database `core` 24GB. Sự kiện lớn
"Đại nhạc hội Ticket2h 2026": 200.000 đơn (180.242 đã trả tiền), 368.662 vé.

| Cách | Thời gian (`EXPLAIN ANALYZE`, gom theo ngày, sự kiện lớn) | Kế hoạch |
|---|---|---|
| Tính thẳng, chưa có index nào bắt đầu bằng `event_id` (như trước M4) | 1.934 ms | `Parallel Seq Scan` trên 10 triệu đơn, rồi hash join với 12,8 triệu `order_items` |
| Tính thẳng, có index `(event_id, …)` của V116 | 1.069 ms | Index scan lấy 180.000 đơn, nhưng vẫn quét cả `order_items` |
| Bảng tổng hợp (câu của `SalesReportStore`) | dưới 1 ms | Index scan 120 dòng `sales_daily_items` + 30 dòng `sales_daily_orders` |

Qua API (`GET /organizer/events/{id}/sales`, gồm JWT, JSON, HTTP): 6–22 ms cho cả ba cách gom (lượt đầu sau khi app
khởi động 94 ms). Tính thẳng chỉ vừa sát 1 giây trên một máy 20 nhân rảnh rỗi, tăng theo số đơn của sự kiện, và mỗi
ban tổ chức mở trang là một lần quét lớn như vậy. Tính lại toàn bộ bảng tổng hợp cho 10 triệu đơn (`rebuildAll`):
28 giây; cả bảng tổng hợp có 336.759 dòng.
