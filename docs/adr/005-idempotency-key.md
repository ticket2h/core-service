# ADR-005: Idempotency key cho tạo đơn

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-14

## Bối cảnh
User bấm hai lần, hoặc mạng chập chờn khiến client gửi lại `POST /orders`. Phải chỉ tạo một đơn, và lần gửi lại trả đúng
đơn đã tạo.

## Các phương án
1. Bảng `idempotency_keys` riêng lưu response
2. Lưu key trên chính `orders` (`idempotency_key`, `request_hash`), unique theo `(user_id, idempotency_key)`
3. Cache Redis key → response

## Quyết định
Chọn phương án 2. Key unique theo cặp với `user_id`, nên hai user tình cờ dùng cùng key không ảnh hưởng nhau.
`request_hash` (SHA-256 của request đã sắp xếp) phát hiện cùng key nhưng khác nội dung → 422.

Điểm mấu chốt: **INSERT đơn là thao tác ghi đầu tiên và được flush ngay** (`saveAndFlush`). Hai request cùng key chạy
song song: request B chờ ở INSERT cho tới khi A commit (B nhận unique violation → đọc lại đơn của A → 200 replay) hoặc
rollback (B tự chạy và nhận cùng lỗi). `OrderPlacement` (không transaction) bọc `OrderCreationService` (một transaction)
ở hai bean khác nhau, nhận diện lỗi trùng key qua tên constraint `uq_orders_user_idempotency_key`.

## Hệ quả và đánh đổi
- Được: không thêm bảng; dữ liệu replay luôn là trạng thái hiện tại của đơn (đã thanh toán thì replay thấy PAID).
- Mất: replay trả trạng thái mới nhất chứ không phải nguyên văn response lần đầu. Với client, như vậy hữu ích hơn.
  Request thất bại (hết vé) không lưu lại, gửi lại cùng key sẽ chạy lại từ đầu.

## Bằng chứng
- `OrderPlacementIT.twenty_parallel_requests_with_the_same_key_create_exactly_one_order`: 20 luồng, 1 đơn, cả 20 trả cùng `orderId`.
- `OrderApiIT`: replay → 200 + `Idempotent-Replayed: true` + cùng body; khác nội dung → 422; thiếu key → 400.
