# ADR-028: Giữ ghế bằng database, Redis chỉ là lớp lọc tuỳ chọn (đã đo so sánh)

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-27
- Liên quan: ADR-002 (giữ ghế có số), ADR-027 (phòng chờ), ADR-029 ở infra (load test), M5 của lộ trình
  ("so sánh giữ ghế bằng database với giữ ghế bằng Redis")

## Bối cảnh
Giữ ghế hiện nay (ADR-002): khoá các ghế theo id tăng dần (`SELECT ... FOR UPDATE`), kiểm tra trống, `UPDATE`. Lúc mở
bán, nhiều người tranh cùng mấy hàng ghế đầu: mỗi người thua vẫn mở một transaction, chờ khoá dòng do người thắng giữ
tới lúc commit, rồi mới biết mình thua. Họ chiếm kết nối database trong lúc chờ.

Câu hỏi thiết kế của lộ trình: số vé, trạng thái ghế lưu ở PostgreSQL hay Redis là chuẩn? Nếu Redis báo còn mà
database đã hết thì sao?

## Các phương án
1. **Chỉ database** (như hiện nay).
2. **Redis là nơi quyết định**: giữ ghế trong Redis (script Lua), ghi database sau (bất đồng bộ). Nhanh nhất, nhưng
   Redis mất dữ liệu (khởi động lại, failover chưa kịp đồng bộ) là mất cả thông tin ai giữ ghế nào; đơn, vé, tiền nằm
   ở database nên hai nơi phải được đối chiếu liên tục.
3. **Redis lọc trước, database quyết định**: một script Lua kiểm tra và đặt khoá cho cả lô ghế trong Redis; lọt qua
   thì giữ ghế trong database như phương án 1; database từ chối thì trả khoá Redis.

## Quyết định
- Database luôn là nơi quyết định. Mặc định **không lọc** (`ticket2h.seat-gate: database`).
- Phương án 3 có sẵn để đo và bật khi cần (`seat-gate: redis`, `RedisSeatGate`): mỗi ghế một khoá
  `t2h:seat:{eventId}:<seatId>` (hash tag: một sự kiện một slot), chủ là `userId:Idempotency-Key` (gửi lại cùng request
  không tự chặn mình), hạn bằng thời gian giữ ghế dài nhất. Sau commit: ghế trả thì xoá khoá, ghế bán thì giữ khoá 7
  ngày (request vào ghế đã bán không chạm database). Redis không trả lời thì bỏ qua lớp lọc (`RedisBreaker`).
- Hai nơi lệch nhau đều an toàn: Redis báo trống mà database đã có người (khoá hết hạn sớm, Redis khởi động lại):
  database từ chối như không có Redis. Redis báo có người mà database đã trống (chưa kịp xoá khoá): khách nhận
  `SEAT_UNAVAILABLE` sớm, ghế hiện lại khi khoá được xoá sau commit hoặc hết hạn.

Vì sao không bật mặc định: lớp lọc chỉ có lợi khi database (chờ khoá dòng) là chỗ nghẽn. Load test của cả hệ thống
(ADR-029) cho thấy lúc mở bán chỗ nghẽn là CPU của core: core luôn chạm trần, PostgreSQL dùng dưới 1/4 phần được cấp.
Bật lớp lọc thì CPU của PostgreSQL giảm 3–6 lần, nhưng p95 của đặt vé không tốt hơn một cách ổn định, và người thua
thử lại nhanh hơn (nhiều request hơn vào đúng chỗ đang nghẽn). Thứ giảm tải thật là phòng chờ (ADR-027): chỉ N người
mỗi giây được đặt vé.

## Hệ quả và đánh đổi
- Được: một phương án đo được, bật bằng một biến môi trường; không có thêm nguồn sự thật nào.
- Mất: bật lên thì thêm một lần gọi Redis cho mỗi lần đặt ghế và mỗi lần ghế đổi trạng thái; thêm một chỗ có thể lệch
  (đều an toàn như trên).
- Khi nào nên bật: database thành chỗ nghẽn vì chờ khoá (nhiều instance core, CPU thừa, kết nối database cạn trong lúc
  mở bán), ví dụ sự kiện vài trăm nghìn người không dùng phòng chờ.

## Bằng chứng
`SeatGateComparisonIT`: 2.000 người cùng lúc tranh 100 ghế, mỗi người 2 ghế liền nhau (cùng một kiểu chọn ghế cho cả
hai lần đo), 40 luồng, pool 20 kết nối, đã chạy thử một lượt cho JVM nóng; hai lần chạy:

| Lớp lọc | Tổng thời gian | Request tới database | Giữ được | p50 | p95 | p99 |
|---|---|---|---|---|---|---|
| database | 542–546 ms | 2.000 | 42–45 | 9,5 ms | 20–22 ms | 27–31 ms |
| redis | 145–157 ms | 43–45 | 43–45 | 2,7 ms | 4,4–4,8 ms | 12–13 ms |

Cả hai đều đúng: số ghế đang giữ trong database bằng đúng 2 × số người giữ được, không ghế nào hai người giữ. Redis chặn
98% người thua trước khi họ chạm database, nhanh gấp khoảng 3,5 lần khi chỉ đo phần giữ ghế.

Đo trên cả hệ thống (k6, ADR-029), 2 instance core mỗi instance 2 CPU, hai lần chạy mỗi cấu hình:

| Lớp lọc | `POST /orders` p95 | CPU PostgreSQL lúc mở bán |
|---|---|---|
| database | 6,6 s / 1,0 s | 70% / 105% |
| redis | 1,9 s / 2,0 s | 28% / 18% |
