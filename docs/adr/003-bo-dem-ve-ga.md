# ADR-003: Bộ đếm vé GA và điểm nghẽn một dòng

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-14

## Bối cảnh
Vé đứng (GA) chỉ có số lượng. 10.000 request giành 1.000 vé phải bán đúng 1.000.

## Các phương án
1. Đếm số vé đã bán bằng `SELECT count/sum` rồi so sánh (kiểm tra rồi mới làm: sai khi song song)
2. Một câu `UPDATE ticket_types SET available_quantity = available_quantity - :q WHERE ... AND available_quantity >= :q`
3. Chia bộ đếm thành nhiều dòng (sharded counter) hoặc dùng Redis `DECRBY`

## Quyết định
Chọn phương án 2 (`GaInventory.take`). Câu UPDATE tự khoá dòng; ở READ COMMITTED, request phải chờ được PostgreSQL
đánh giá lại `WHERE` trên phiên bản mới nhất (EvalPlanQual), nên khi vé cuối đã bị lấy thì điều kiện thành sai và
cập nhật 0 dòng. Ràng buộc `ticket_types_quantity_check` (0 ≤ available ≤ total) là lưới an toàn cuối cùng.

## Hệ quả và đánh đổi
- Được: một câu lệnh, không cần `SELECT ... FOR UPDATE`, đúng tuyệt đối.
- Mất: mọi người mua GA của cùng một loại vé xếp hàng trên **một dòng**. Đây là điểm nghẽn đã biết khi tải lớn;
  M5 sẽ đo bằng k6 và thử phương án 3.

## Bằng chứng
- `GaInventoryConcurrencyIT`: 10.000 request / 64 luồng → đúng 1.000 thành công, còn 0 vé.
- `OrderPlacementIT.ten_thousand_orders_for_one_thousand_ga_tickets`: 10.000 lượt đặt qua toàn luồng tạo đơn,
  2.500 user → đúng 1.000 vé được giữ.
- Còn 3 vé mà xin 5 → bị từ chối, vẫn còn 3; set `available_quantity = -1` bằng tay → DB từ chối.
