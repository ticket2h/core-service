# ADR-002: Cách giữ ghế khi nhiều người cùng chọn

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-14

## Bối cảnh
Lúc mở bán, hàng trăm người bấm cùng một ghế đẹp. Đúng một người được giữ; người giữ nhiều ghế `{A, B}` và
`{B, A}` cùng lúc không được deadlock. Ghế HELD quá `held_until` coi như còn trống (hết hạn lười).

## Các phương án
1. Đọc rồi cập nhật, không khoá (cách AI hay viết)
2. Optimistic locking với `@Version`
3. `SELECT ... FOR UPDATE` theo `ORDER BY id`, kiểm tra trong lúc giữ khoá, rồi `UPDATE` có điều kiện
4. Redis `SET NX EX`

## Quyết định
Chọn phương án 3 (`SeatHoldService.hold`). Transaction thứ hai phải chờ ở `FOR UPDATE`; khi được chạy, PostgreSQL trả
phiên bản mới nhất của dòng (đã HELD) nên phát hiện ghế bận. Khoá theo id tăng dần nên hai bên luôn tranh ghế có id
nhỏ nhất trước, không ai giữ một ghế rồi chờ ghế kia. Câu `UPDATE` lặp lại điều kiện để phòng thủ.

## Hệ quả và đánh đổi
- Được: đúng tuyệt đối; báo được chính xác ghế nào bận (đọc trong lúc giữ khoá); không phụ thuộc thêm hạ tầng.
- Mất: request tranh cùng ghế phải xếp hàng chờ khoá. Với ghế có số, mỗi ghế chỉ có vài người tranh thật sự nên chấp nhận được.
- So với `@Version`: khi tranh chấp nhiều, optimistic để nhiều transaction làm xong rồi mới bị huỷ, và phải đọc lại
  sau khi thất bại mới biết ghế nào hết. Phù hợp hơn cho chỗ ít tranh chấp (BTC sửa sự kiện).
- Redis: nhanh nhưng thêm một nguồn sự thật phải đồng bộ với DB; để dành cho M5 nếu đo thấy DB nghẽn.

Mọi chỗ khác chạm vào nhiều ghế cũng khoá theo id trước: `release()` (hết hạn, huỷ) và `extend()` (gia hạn khi thanh
toán) gọi `lockHeldSeats()` (`ORDER BY id FOR UPDATE`) rồi mới UPDATE. Một câu `UPDATE ... WHERE order_id = ?` trần khoá
dòng theo thứ tự quét bảng (theo vị trí vật lý), không theo id.

## Bằng chứng
- Phương án 1 (thí nghiệm, pool 20 kết nối, 200 request cùng một ghế, 5 lần): **20, 20, 20, 19, 20** request cùng
  "giữ được" ghế. Gần bằng số kết nối: mọi transaction đang chạy cùng lúc đều đọc thấy AVAILABLE.
- Phương án 3: `SeatClaimConcurrencyIT.only_one_of_200_concurrent_buyers_gets_the_same_seat` luôn đúng 1;
  `overlapping_multi_seat_holds_never_deadlock` 50 vòng `{A,B}`/`{B,A}` không deadlock; `./mvnw verify` xanh nhiều lần liên tiếp.
- Review độc lập phát hiện `release()` ban đầu dùng UPDATE trần. Test `releasing_expired_seats_while_someone_retakes_them_never_deadlocks`
  (job trả 4 ghế quá hạn của A trong lúc C giữ lại chính 4 ghế đó theo thứ tự ngược) **đỏ ngay vòng đầu với
  `ERROR: deadlock detected`**; khoá theo id trước thì xanh 50/50 vòng.
