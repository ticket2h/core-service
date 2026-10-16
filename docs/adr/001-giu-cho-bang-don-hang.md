# ADR-001: Gộp giữ chỗ vào đơn hàng PENDING_PAYMENT

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-14

## Bối cảnh
Khách chọn ghế/vé rồi cần vài phút để thanh toán. Trong thời gian đó phải giữ chỗ cho họ, hết hạn thì trả lại.
Lộ trình ban đầu có bảng `holds` riêng.

## Các phương án
1. Bảng `holds` riêng: giữ chỗ trước, tạo đơn khi bấm thanh toán.
2. Đơn `PENDING_PAYMENT` chính là giữ chỗ: tạo đơn = giữ hạn mức, vé GA, ghế trong một transaction; `orders.expires_at` là hạn giữ.

## Quyết định
Chọn phương án 2.

## Hệ quả và đánh đổi
- Được: ít bảng và ít trạng thái hơn; một transaction duy nhất cho tạo đơn (bước nào lỗi thì rollback hết);
  hết hạn và huỷ dùng chung `OrderRelease`; webhook chỉ cần khoá đơn rồi ghế.
- Mất: chưa có bước "giữ ghế rồi mới tạo đơn" (ví dụ giữ ghế khi đang chọn trên sơ đồ). Mỗi lần giữ là một đơn,
  nên bảng `orders` có nhiều đơn EXPIRED. Nếu sau này cần giữ tạm khi đang chọn, thêm cơ chế riêng (Redis TTL) chứ không đổi lại.

## Bằng chứng
- `OrderPlacementIT.one_taken_seat_rolls_back_everything`: một ghế bận ở giữa danh sách → không có đơn, GA và hạn mức không đổi.
- `OrderExpiryIT`: hết hạn trả lại ghế, GA, hạn mức; ghế đã được người khác giữ lại vẫn thuộc người đó.
