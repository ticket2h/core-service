# Architecture Decision Records

Mỗi file ghi một quyết định thiết kế: vấn đề, các phương án, chọn gì, đánh đổi gì, bằng chứng.
Mẫu ở phụ lục E2 của `ticketrush-m2-m3-huong-dan.md`. Đặt tên `NNN-ten-ngan.md`, không sửa ADR đã chấp nhận;
quyết định thay đổi thì viết ADR mới và đánh dấu ADR cũ là "Bị thay thế bởi ADR-NNN".

| ADR | Chủ đề |
|---|---|
| [001](001-giu-cho-bang-don-hang.md) | Gộp giữ chỗ vào đơn `PENDING_PAYMENT` thay vì bảng `holds` riêng |
| [002](002-giu-ghe-co-so.md) | Cách giữ ghế có số khi nhiều người cùng chọn |
| [003](003-bo-dem-ve-ga.md) | Bộ đếm vé GA và điểm nghẽn một dòng |
| [004](004-han-muc-ve-moi-user.md) | Hạn mức vé mỗi user và quan hệ với chuyển nhượng |
| [005](005-idempotency-key.md) | Idempotency key cho tạo đơn |
| [006](006-thanh-toan-tre-va-hoan-tien.md) | Thanh toán trễ và hoàn tiền |
| [007](007-outbox-kafka-phat-ve-email.md) | Outbox, Kafka, phát vé bất đồng bộ, chấp nhận email trùng |
| [008](008-qr-ky-ed25519.md) | QR ký Ed25519 thay vì token ngẫu nhiên lưu DB |
| [009](009-chuyen-nhuong-ve.md) | Quy tắc chuyển nhượng vé, lộ thông tin email người nhận |
| [010](010-ai-duoc-mua-ve.md) | Chỉ tài khoản CUSTOMER được tạo đơn |
| [016](016-bao-cao-doanh-thu-bang-tong-hop.md) | M4: báo cáo doanh thu bằng bảng tổng hợp, cộng dồn qua hàng đợi |
| [017](017-xuat-csv-lon.md) | M4: xuất CSV lớn, đọc theo lô keyset khi cần gọi service khác, đọc theo luồng khi không |
| [018](018-phan-trang-keyset.md) | M4: phân trang keyset cho danh sách dài (cả `GET /admin/users` của identity) |
| [019](019-partition-checkin-logs.md) | M4: chia `checkin_logs` thành bảng con theo tháng |
| [020](020-nhap-ghe-tu-csv.md) | M4: nhập sơ đồ ghế từ CSV, kiểm tra hết rồi mới ghi, báo mọi dòng lỗi |

Số ADR chạy chung cho cả hệ thống. Quyết định cấp hệ thống (tách microservice, database riêng, công cụ sinh dữ liệu
dùng chung: ADR-011…015, 021) nằm ở [infra/docs/adr](https://github.com/ticket2h/infra/tree/main/docs/adr); 016–020
là thiết kế bên trong core nên nằm ở đây.
