# ADR-006: Thanh toán trễ và hoàn tiền

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-14

## Bối cảnh
Webhook thanh toán có thể đến sau khi đơn hết hạn, sau khi khách huỷ, hoặc cho phiên thứ hai khi đơn đã trả ở tab khác.
Tiền đã trừ là sự thật; câu hỏi là giao vé hay hoàn tiền.

## Chính sách

| Khi webhook thành công đến | Kết quả |
|---|---|
| Đơn chờ, chưa hết hạn | PAID |
| Đơn chờ, đã quá hạn nhưng job chưa dọn, ghế còn nguyên | PAID (không ai bị thiệt) |
| Đơn chờ, đã quá hạn, một số ghế đã thuộc người khác | Hoàn tiền (`SEATS_TAKEN`) |
| Đơn EXPIRED | Hoàn tiền (`ORDER_EXPIRED`) |
| Đơn CANCELLED | Hoàn tiền (`ORDER_CANCELLED`) |
| Đơn đã PAID bằng payment khác | Hoàn tiền payment thứ hai (`ORDER_ALREADY_PAID`) |

Webhook khoá đơn → khoá payment → khoá các ghế đơn đang giữ (`ORDER BY id FOR UPDATE`) rồi mới đếm, để người khác
không chen vào giữa lúc đếm và lúc chuyển SOLD. Vé GA không cần kiểm tra: chỉ được trả lại khi đơn EXPIRED/CANCELLED.

Hoàn tiền không gọi trong transaction webhook. Trạng thái `REFUND_PENDING` là ý định hoàn tiền đã lưu bền vững;
`RefundJob` "thuê" việc (đẩy `next_refund_attempt_at` thêm 2 phút, `SKIP LOCKED`), gọi MockPay với
`idempotencyKey = payment.id` ngoài transaction, lỗi thì lùi lịch 1, 2, 5, 10, 30 phút rồi mỗi giờ; từ lần thứ 8 log ERROR cần xử lý tay.

## Các phương án đã loại
- Cố giữ lại ghế cho đơn đã EXPIRED (nếu ghế còn trống thì "hồi sinh" đơn): thêm một luồng chuyển trạng thái ngược
  (EXPIRED → PAID), phải khoá lại ghế, GA, hạn mức theo đúng thứ tự, dễ sai mà lợi ích nhỏ vì đã có `expiry-grace` 1 phút
  và gia hạn 15 phút khi bắt đầu thanh toán.

## Hệ quả và đánh đổi
- Được: không bao giờ bán một ghế hai lần; không bao giờ có trạng thái nửa vời.
- Mất: một số khách hiếm hoi trả tiền rồi được hoàn thay vì nhận vé.
- Webhook bị mất hẳn (`dropWebhook`) thì đơn hết hạn nhưng tiền đã trừ phía cổng. Chưa xử lý ở M2; M6 thêm job đối soát
  gọi `GET /api/v1/payments/by-ref/{merchantRef}`.

## Bằng chứng
- `LatePaymentIT` (6 test), gồm 50 vòng đua `expireOne` với webhook (có trễ ngẫu nhiên để cả hai thứ tự đều xảy ra,
  ví dụ một lần chạy: 42 lần PAID, 8 lần EXPIRED): luôn là (PAID/FULFILLED, SOLD) hoặc (EXPIRED, REFUND_PENDING, AVAILABLE).
- Job hoàn tiền: MockPay 503 hai lần rồi thành công → REFUNDED, `refund_attempts = 2`, cả 3 lần gọi cùng `idempotencyKey`.
