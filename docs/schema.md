# Thiết kế bảng

Nguồn sự thật là các file Flyway trong `src/main/resources/db/migration`. Đây là database `core` của core-service; identity-service (`users`, `organizers`) và notification-service (`notification_deliveries`) có database riêng. Các cột `user_id`, `organizer_id`... trong core chỉ là id lấy từ JWT, không có khoá ngoại (ADR-013 ở [infra/docs/adr](https://github.com/ticket2h/infra/tree/main/docs/adr)). Tài liệu này tóm tắt ý đồ; muốn đổi bảng thì thêm migration mới.

| Migration | Nội dung |
|---|---|
| `V1__m1_identity.sql` | `organizers`, `users` (một vai trò mỗi tài khoản; ORGANIZER gắn `organizer_id`) |
| `V2__m1_catalog.sql` | `venues`, `events` (cửa sổ bán vé, `search_text` đã bỏ dấu, index trigram), `ticket_types`, `seats` |
| `V100__m2_orders.sql` | `events.max_tickets_per_user`, `orders`, `order_items`, `purchase_quotas` |
| `V101__m2_inventory.sql` | Bộ đếm GA trên `ticket_types`, trạng thái giữ/bán của `seats` |
| `V102__m2_payments.sql` | `payments`, `payment_webhook_logs` |
| `V110__m3_orders_fulfilled.sql` | Trạng thái `FULFILLED`, `fulfilled_at` |
| `V111__m3_tickets.sql` | `event_staff`, `tickets`, `ticket_transfers`, `checkin_logs` |
| `V112__m3_outbox_notifications.sql` | `outbox_events`, `notification_deliveries` |
| `V113__notification_moved_out.sql` | Xoá `notification_deliveries` (chuyển sang notification-service, ADR-011 ở [infra/docs/adr](https://github.com/ticket2h/infra/tree/main/docs/adr)) |
| `V114__identity_moved_out.sql` | Bỏ 10 khoá ngoại tới `users`/`organizers`, xoá hai bảng đó (chuyển sang identity-service, ADR-013) |
| `V115__m4_sales_report.sql` | `orders.sales_channel`; hàng đợi `sales_rollup_queue`; bảng tổng hợp `sales_daily_items`, `sales_daily_orders` (ADR-016) |
| `V116__m4_list_export_indexes.sql` | Index `orders (event_id, created_at DESC, id DESC)` cho danh sách đơn keyset (ADR-018), `tickets (event_id, issued_at, id)` cho file danh sách khách (ADR-017) |
| `V117__m4_checkin_logs_partition.sql` | `checkin_logs` thành bảng partition theo tháng (`scanned_at`), khoá chính `(id, scanned_at)`, bảng `checkin_logs_default` hứng dòng ngoài các tháng đã tạo (ADR-019) |
| `V118__m6_payment_reconciliation.sql` | `payments.next_reconcile_at`, `reconcile_attempts`, `reconciled_at`; index một phần cho payment chưa có kết quả (ADR-022) |
| `V119__m6_audit_logs.sql` | `audit_logs` partition theo tháng (`occurred_at`), chỉ ghi thêm (trigger chặn UPDATE, DELETE), ADR-023 ở infra |
| `V120__m5_seat_versions.sql` | `seats.version`: tăng mỗi lần ghế đổi trạng thái, sơ đồ ghế trực tiếp dùng để không áp thay đổi cũ (ADR-024) |
| `V121__m5_waiting_room.sql` | `events.waiting_room_admit_per_second` (NULL: không có phòng chờ), index một phần cho job cho người vào (ADR-027) |

## Ràng buộc là lớp chặn cuối cùng

| Quy tắc | Ràng buộc |
|---|---|
| Đóng bán trước giờ diễn | `events_sale_window_check` |
| Ghế thuộc loại vé của chính sự kiện đó | `fk_seats_ticket_type_same_event` → `uq_ticket_types_id_event` |
| Không trùng vị trí ghế | `uq_seats_position` |
| Một request mua (theo key) chỉ tạo một đơn | `uq_orders_user_idempotency_key` |
| Trạng thái ghế nhất quán với đơn giữ/bán | `seats_state_check` |
| Không âm, không vượt tổng vé GA | `ticket_types_quantity_check` |
| Hạn mức không âm | `purchase_quotas_non_negative` |
| Mỗi giao dịch của cổng chỉ ghi nhận một lần | `uq_payments_gateway_transaction`, `uq_payments_gateway_payment` |
| Phát vé lặp lại không tạo thêm vé | `uq_tickets_order_item_seq` |
| Một ghế tối đa một vé còn hiệu lực | `uq_tickets_active_seat` (partial, loại trừ `VOID`) |
| Kênh bán là giá trị đã biết | `orders_sales_channel_check` |
| Mỗi đơn chờ cộng vào báo cáo đúng một lần | `sales_rollup_queue` khoá chính `order_id` |
| Số liệu tổng hợp không âm | `sales_daily_items_non_negative`, `sales_daily_orders_non_negative` |
| Lịch sử thao tác không sửa, không xoá được | trigger `audit_logs_append_only`; `audit_logs_user_has_id`, `audit_logs_system_has_name` |
| Số người vào phòng chờ mỗi giây hợp lệ | `events_waiting_room_rate_check` (1–10.000 hoặc NULL) |

## Bảng nào đổi bằng gì

| Bảng | Cách đổi trạng thái |
|---|---|
| `orders`, `payments` | JPA entity; khoá `SELECT ... FOR UPDATE` rồi gọi method của entity |
| `seats`, `ticket_types` (GA), `purchase_quotas`, `tickets` | SQL có điều kiện qua `NamedParameterJdbcTemplate`, kiểm tra số dòng. Ghế: mọi câu đổi trạng thái tăng `version` và `RETURNING` các ghế đã đổi cho sơ đồ trực tiếp (`SeatHoldService`) |
| `events`, `venues` | SQL (`CatalogStore`) |
| `outbox_events`, `payment_webhook_logs`, `checkin_logs` | SQL |
| `sales_rollup_queue`, `sales_daily_items`, `sales_daily_orders` | SQL (`SalesRollupStore`): ghi hàng đợi lúc đơn PAID, job lấy-xoá-cộng trong một câu lệnh |
| bảng con `checkin_logs_YYYY_MM` | `CheckinLogPartitions` tạo trước 3 tháng, mỗi ngày một lần |
| `payments` (cột đối soát) | JDBC (`PaymentReconciliationSteps`): nhận việc bằng `SKIP LOCKED`; entity chỉ ghi `next_reconcile_at` lúc tạo |
| `audit_logs` | Chỉ `INSERT` (`AuditLog.record`, cùng transaction với thay đổi); bảng con theo tháng do `AuditLogPartitions` tạo |

Ngoài PostgreSQL (M5), Redis chỉ giữ thứ mất được: hàng chờ của phòng chờ, xô giới hạn request, số thứ tự của kênh
thay đổi ghế, khoá của lớp lọc ghế. Redis khởi động lại thì người đang xếp hàng phải xếp lại; không mất đơn, vé hay
tiền.
