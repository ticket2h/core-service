# ADR-009: Quy tắc chuyển nhượng vé

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-14

## Bối cảnh
Khách chuyển vé cho bạn bằng email. QR cũ phải mất hiệu lực ngay. Báo "không tìm thấy người nhận" giúp kẻ xấu dò email
nào đã đăng ký.

## Quyết định
- Một câu UPDATE có điều kiện: `owner_user_id = :to, token_version = token_version + 1 WHERE id = :ticket AND
  owner_user_id = :from AND status = 'VALID'`. Đua với check-in: hai câu lệnh nhắm cùng một dòng nên chạy lần lượt,
  bên đến sau thấy điều kiện của mình không còn đúng.
- Không cho chuyển trong vòng `ticket2h.ticket.transfer-cutoff` (2 giờ) trước giờ diễn.
- Người nhận phải là tài khoản CUSTOMER; không chuyển cho chính mình.
- **Dò email:** vẫn trả `404 RECIPIENT_NOT_FOUND` để khách biết gõ sai email, nhưng chỉ sau khi đã kiểm tra người gọi
  sở hữu vé đó (vé của người khác → 404 trước khi tra email). Kẻ muốn dò email phải mua vé trước.
  M5 sẽ thêm rate limit theo user cho endpoint này.
- Gửi email cho cả hai người qua outbox (`TicketTransferred`); người nhận có QR mới.

## Hệ quả và đánh đổi
- Được: QR cũ vô hiệu ngay; luôn đúng một trong hai (chuyển nhượng hoặc check-in) thành công.
- Mất: vẫn lộ việc một email có tài khoản với người đã mua vé. Phương án không lộ (luôn trả 200, gửi lời mời đăng ký)
  phức tạp hơn nhiều; để sau.

## Bằng chứng
- `TicketTransferIT` (9 test): QR cũ → TOKEN_REVOKED, QR mới → OK; chuyển tiếp lần hai → `token_version = 3`;
  50 vòng chuyển nhượng song song với check-in → luôn đúng một bên thắng; hạn mức người nhận không đổi.
