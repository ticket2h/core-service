-- M4: chia checkin_logs thành từng bảng con theo tháng (partition theo scanned_at, ADR-019 trong docs/adr).
-- Sau 2 năm bảng có 30 triệu dòng: truy vấn theo khoảng thời gian chỉ đọc các tháng liên quan (partition pruning),
-- và bỏ dữ liệu cũ bằng DROP/DETACH một bảng con thay vì DELETE hàng triệu dòng.
-- Job CheckinLogPartitions tạo trước bảng con cho các tháng sắp tới. Bảng DEFAULT hứng dòng không thuộc tháng nào
-- đã tạo, để check-in ở cổng không bao giờ lỗi chỉ vì thiếu bảng con.

ALTER TABLE checkin_logs RENAME TO checkin_logs_old;
ALTER TABLE checkin_logs_old RENAME CONSTRAINT checkin_logs_pkey TO checkin_logs_old_pkey;
ALTER INDEX idx_checkin_logs_event_time RENAME TO idx_checkin_logs_old_event_time;
ALTER SEQUENCE checkin_logs_id_seq RENAME TO checkin_logs_old_id_seq;

-- Khoá chính của bảng partition phải chứa cột dùng để chia (scanned_at)
CREATE TABLE checkin_logs (
    id             bigint GENERATED ALWAYS AS IDENTITY,
    ticket_id      uuid,
    event_id       uuid,
    staff_user_id  uuid NOT NULL,
    gate           varchar(50) NOT NULL,
    result         varchar(30) NOT NULL,
    scanned_at     timestamptz NOT NULL,
    CONSTRAINT pk_checkin_logs PRIMARY KEY (id, scanned_at)
) PARTITION BY RANGE (scanned_at);

-- Index khai báo trên bảng cha được tạo trên mọi bảng con, kể cả bảng con tạo sau này
CREATE INDEX idx_checkin_logs_event_time ON checkin_logs (event_id, scanned_at);

CREATE TABLE checkin_logs_default PARTITION OF checkin_logs DEFAULT;

-- Bảng con theo tháng (giờ UTC), từ tháng của dòng cũ nhất (hoặc tháng này) tới 3 tháng sau
DO $$
DECLARE
    month_start date := date_trunc('month', least(coalesce((SELECT min(scanned_at) FROM checkin_logs_old), now()),
                                                  now()) AT TIME ZONE 'UTC')::date;
    last_month  date := (date_trunc('month', now() AT TIME ZONE 'UTC') + interval '3 months')::date;
BEGIN
    WHILE month_start <= last_month LOOP
        EXECUTE format('CREATE TABLE %I PARTITION OF checkin_logs FOR VALUES FROM (%L) TO (%L)',
                       'checkin_logs_' || to_char(month_start, 'YYYY_MM'),
                       month_start::timestamp AT TIME ZONE 'UTC',
                       (month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC');
        month_start := (month_start + interval '1 month')::date;
    END LOOP;
END $$;

INSERT INTO checkin_logs (id, ticket_id, event_id, staff_user_id, gate, result, scanned_at)
OVERRIDING SYSTEM VALUE
SELECT id, ticket_id, event_id, staff_user_id, gate, result, scanned_at FROM checkin_logs_old;

SELECT setval(pg_get_serial_sequence('checkin_logs', 'id'),
              coalesce((SELECT max(id) FROM checkin_logs_old), 0) + 1, false);

DROP TABLE checkin_logs_old;
