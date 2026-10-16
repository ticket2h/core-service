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

## Bảng nào đổi bằng gì

| Bảng | Cách đổi trạng thái |
|---|---|
| `orders`, `payments` | JPA entity; khoá `SELECT ... FOR UPDATE` rồi gọi method của entity |
| `seats`, `ticket_types` (GA), `purchase_quotas`, `tickets` | SQL có điều kiện qua `NamedParameterJdbcTemplate`, kiểm tra số dòng |
| `events`, `venues` | SQL (`CatalogStore`) |
| `outbox_events`, `payment_webhook_logs`, `checkin_logs` | SQL |
