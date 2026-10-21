# ADR-019: Chia `checkin_logs` thành bảng con theo tháng

- Trạng thái: Đã chấp nhận
- Ngày: 2026-10-21
- Liên quan: ADR-017 (file lịch sử soát vé)

## Bối cảnh
Mọi lượt quét ở cổng đều được ghi lại, kể cả lượt bị từ chối (M3). Sau 2 năm có khoảng 30 triệu dòng, và chỉ tăng
thêm: không ai sửa log cũ. Đọc thì gần như luôn theo một khoảng thời gian (lịch sử soát vé của một đêm diễn). Xoá log
cũ (ví dụ giữ 2 năm) bằng `DELETE ... WHERE scanned_at < ...` trên hàng chục triệu dòng thì chậm, khoá lâu và để lại
nhiều chỗ trống cần VACUUM. Lộ trình M4 gợi ý "partition bảng log theo tháng"; migration V111 đã ghi chú sẵn việc này.

## Các phương án
1. Giữ một bảng, thêm index `(event_id, scanned_at)` (đã có). Đọc đủ nhanh, nhưng xoá dữ liệu cũ vẫn là DELETE lớn,
   và index cứ to mãi.
2. **Partition theo khoảng thời gian (`PARTITION BY RANGE (scanned_at)`), mỗi tháng một bảng con.** Truy vấn có điều
   kiện trên `scanned_at` chỉ đọc bảng con liên quan (partition pruning); bỏ một tháng cũ là `DETACH`/`DROP` một bảng.
3. Partition theo `event_id` (hash/list): đọc theo sự kiện tốt, nhưng không giúp gì cho việc bỏ dữ liệu cũ, và số
   sự kiện tăng mãi.

## Quyết định
Chọn phương án 2 (`V117__m4_checkin_logs_partition.sql`).

- Đổi tên bảng cũ, tạo bảng partition mới cùng tên và cột, chép dữ liệu (`OVERRIDING SYSTEM VALUE` để giữ id),
  `setval` sequence, xoá bảng cũ. Đổi tên cả constraint, index, sequence cũ trước để tên mới không bị trùng.
- Khoá chính bắt buộc chứa cột dùng để chia: `(id, scanned_at)`. Không bảng nào có khoá ngoại trỏ vào `checkin_logs`
  (ghi trong CLAUDE.md). Index `(event_id, scanned_at)` khai báo trên bảng cha, PostgreSQL tự tạo trên mọi bảng con.
  Cột identity trên bảng partition cần PostgreSQL 17 (đang dùng 17).
- Bảng con theo tháng giờ UTC, tên `checkin_logs_YYYY_MM`. Migration tạo từ tháng của dòng cũ nhất tới 3 tháng sau;
  job `CheckinLogPartitions.ensureAhead` chạy lúc khởi động và mỗi ngày, luôn giữ sẵn bảng cho 3 tháng tới. Công cụ
  sinh dữ liệu tạo bảng cho cả 2 năm lịch sử.
- Bảng `checkin_logs_default` hứng mọi dòng không thuộc tháng nào đã tạo: check-in ở cổng không bao giờ lỗi chỉ vì
  thiếu bảng con.

## Hệ quả và đánh đổi
- Được: lịch sử soát vé của một đêm diễn chỉ đọc một (hoặc hai) bảng con; bỏ dữ liệu cũ là một lệnh DDL nhanh.
- Mất: thêm một job phải chạy đều. Nếu job ngừng quá 3 tháng, dòng mới rơi vào bảng default, và tạo bảng con cho
  tháng đó sẽ lỗi ("updated partition constraint for default partition would be violated") cho tới khi chuyển các
  dòng đó đi: tạo bảng con mới với tên khác, `INSERT ... SELECT` từ default, `DELETE` khỏi default, rồi `ATTACH`. Job
  ghi log lỗi và đi tiếp các tháng khác, không làm hỏng check-in.
- Truy vấn không có điều kiện thời gian (ví dụ mọi lượt quét của một vé) phải đọc mọi bảng con; hiện chưa có truy vấn
  nào như vậy trên đường nóng.

## Bằng chứng
- `CheckinLogPartitionsIT`: job tạo đủ 4 tháng (tháng này + 3), chạy lại không tạo thêm; dòng 23:59:59 ngày 31/7 và
  00:00:00 ngày 1/8 vào đúng hai bảng con; `EXPLAIN` truy vấn 2 ngày của tháng 2 chỉ có `checkin_logs_2027_02`; tháng
  đã có dòng trong bảng default thì bỏ qua (ghi log), tháng sau vẫn tạo được, dữ liệu không mất.
- `OrganizerExportIT.check_in_history...`, `ExportMemoryIT`: đọc lịch sử soát vé theo khoảng ngày qua bảng partition.

## Đo trên dữ liệu sinh

Dữ liệu sinh bằng `infra/scripts/datagen.sh` (ADR-021) trên máy dev (20 nhân, 30GB RAM, NVMe, PostgreSQL 17 cấu hình
mặc định của image, sau `VACUUM ANALYZE`): 2 triệu tài khoản, 2.001 sự kiện, 10.000.000 đơn, 12,8 triệu dòng
`order_items`, 9 triệu thanh toán, 18,5 triệu vé, 6,1 triệu ghế, 30 triệu lượt quét; database `core` 24GB. Sự kiện lớn
"Đại nhạc hội Ticket2h 2026": 200.000 đơn (180.242 đã trả tiền), 368.662 vé. Có 31 bảng con (tháng 9/2024 tới tháng 2/2027 và bảng default, bảng
default 0 dòng).

| Truy vấn (đếm lượt quét của một sự kiện đã diễn) | Bảng con được đọc | Thời gian |
|---|---|---|
| Có điều kiện `scanned_at` trong 2 ngày quanh giờ diễn | 1 (`checkin_logs_2026_09`) | 8,3 ms |
| Không có điều kiện thời gian | 31 | 12,3 ms |

Với truy vấn có index tốt, partition không làm nhanh hơn nhiều (mỗi bảng con có index riêng, đọc một index nhỏ hay 31
index nhỏ đều nhanh). Lợi ích chính là vận hành: bỏ một tháng cũ là `DROP` một bảng con thay vì `DELETE` 1–2 triệu
dòng, và VACUUM, index của mỗi tháng nhỏ. Đây là đánh đổi nên biết trước khi chọn partition chỉ vì "bảng to".
