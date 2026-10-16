-- Microservice bước 2: tài khoản và ban tổ chức chuyển sang identity-service (database "identity").
-- core chỉ giữ id người dùng và id ban tổ chức, lấy từ JWT do identity ký (claim sub, organizerId), nên không
-- còn bảng nào để khoá ngoại trỏ tới. Index trên các cột user_id/organizer_id vẫn giữ để truy vấn.
-- Xem infra/docs/adr/013. Tên constraint là tên PostgreSQL tự đặt cho khoá ngoại khai báo ở V1, V2, V100, V111.

ALTER TABLE events           DROP CONSTRAINT events_organizer_id_fkey;
ALTER TABLE venues           DROP CONSTRAINT venues_organizer_id_fkey;
ALTER TABLE orders           DROP CONSTRAINT orders_user_id_fkey;
ALTER TABLE purchase_quotas  DROP CONSTRAINT purchase_quotas_user_id_fkey;
ALTER TABLE event_staff      DROP CONSTRAINT event_staff_user_id_fkey;
ALTER TABLE tickets          DROP CONSTRAINT tickets_owner_user_id_fkey;
ALTER TABLE tickets          DROP CONSTRAINT tickets_checked_in_by_fkey;
ALTER TABLE ticket_transfers DROP CONSTRAINT ticket_transfers_from_user_id_fkey;
ALTER TABLE ticket_transfers DROP CONSTRAINT ticket_transfers_to_user_id_fkey;

DROP TABLE users;
DROP TABLE organizers;
