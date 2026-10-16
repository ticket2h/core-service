# ADR-004: Hạn mức vé mỗi user

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-14

## Bối cảnh
Mỗi user tối đa `events.max_tickets_per_user` vé (mặc định 4) cho một sự kiện, kể cả khi gửi song song từ nhiều tab.

## Các phương án
1. `SELECT sum(quantity)` các đơn của user rồi so sánh: hai tab cùng đọc thấy 2, cùng mua thêm 2 → 6
2. Bảng `purchase_quotas` + một câu `INSERT ... ON CONFLICT DO UPDATE ... WHERE reserved + :q <= :limit`
3. Khoá dòng user (`SELECT ... FROM users FOR UPDATE`) rồi đếm

## Quyết định
Chọn phương án 2 (`PurchaseQuotas.reserve`). `ON CONFLICT DO UPDATE` khoá dòng nên các request của cùng user chạy
lần lượt; chưa có dòng thì INSERT thẳng, vì vậy kiểm tra `quantity <= limit` trong Java trước.
Hết hạn/huỷ trả lại hạn mức (`OrderRelease`).

**Hạn mức tính trên vé đã giữ hoặc đã mua.** Chuyển nhượng vé không đổi hạn mức của người gửi lẫn người nhận:
người gửi đã dùng hạn mức khi mua; người nhận không mua nên không tốn hạn mức.

## Hệ quả và đánh đổi
- Được: đúng khi song song; không khoá bảng `users` (tránh tranh chấp với các luồng khác).
- Mất: có thể lách hạn mức bằng cách mua rồi chuyển cho tài khoản khác, rồi mua tiếp. Chấp nhận ở M3; nếu cần chặn
  phe vé, M5 có thể tính hạn mức theo người cầm vé hoặc giới hạn số lần chuyển.

## Bằng chứng
- `PurchaseQuotaConcurrencyIT.parallel_tabs_cannot_exceed_purchase_limit`: 10 tab song song xin 2 vé, hạn mức 4 → đúng 2 tab thành công, `reserved = 4`.
- Xin 5 khi hạn mức 4 → từ chối, không tạo dòng; trả lại 2 → mua tiếp được 2.
- `TicketTransferIT.recipient_purchase_quota_is_unchanged`.
