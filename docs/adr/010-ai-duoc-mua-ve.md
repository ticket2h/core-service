# ADR-010: Chỉ tài khoản CUSTOMER được tạo đơn

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-14

## Bối cảnh
Bước 12.1: user vai trò ORGANIZER có được tạo đơn không? Mỗi tài khoản có đúng một vai trò.

## Quyết định
`POST /orders` chỉ cho vai trò CUSTOMER (chặn trong `SecurityConfig`); ORGANIZER, STAFF, ADMIN nhận 403.
Nhân viên ban tổ chức muốn mua vé thì dùng tài khoản khách hàng riêng.

## Hệ quả và đánh đổi
- Được: tách bạch quyền; nhân viên soát vé hay BTC không tự giữ vé cho mình qua tài khoản có quyền cao.
- Mất: một người có thể cần hai tài khoản. Vé mời/vé tặng của BTC là tính năng riêng (chưa làm).

## Bằng chứng
- `OrderAccessIT.organizer_and_staff_cannot_place_orders`.
