-- M6: lịch sử thao tác nhạy cảm (ADR-023 trong infra/docs/adr): ban tổ chức đổi giá, sửa sự kiện, phân công nhân
-- viên, tải danh sách khách; hệ thống hoàn tiền, đối soát. Ghi trong CÙNG transaction với thay đổi được ghi lại (thay
-- đổi bị rollback thì dòng audit cũng không còn). Chỉ ghi thêm: trigger chặn UPDATE và DELETE.
--
-- Partition theo tháng như checkin_logs (ADR-019): bỏ dữ liệu cũ là DROP một bảng con, không DELETE từng dòng (DELETE
-- cũng bị trigger chặn). Khoá chính phải chứa cột dùng để chia (occurred_at).
CREATE TABLE audit_logs (
    id            uuid NOT NULL,
    occurred_at   timestamptz NOT NULL,
    actor_type    varchar(10) NOT NULL,
    actor_id      uuid,
    actor_role    varchar(20),
    actor_name    varchar(60),
    organizer_id  uuid,
    event_id      uuid,
    action        varchar(50) NOT NULL,
    target_type   varchar(30) NOT NULL,
    target_id     uuid,
    details       jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT pk_audit_logs PRIMARY KEY (id, occurred_at),
    CONSTRAINT audit_logs_actor_type_check CHECK (actor_type IN ('USER', 'SYSTEM')),
    CONSTRAINT audit_logs_user_has_id CHECK (actor_type <> 'USER' OR actor_id IS NOT NULL),
    CONSTRAINT audit_logs_system_has_name CHECK (actor_type <> 'SYSTEM' OR actor_name IS NOT NULL)
) PARTITION BY RANGE (occurred_at);

-- Trang quản trị: mới nhất trước, phân trang keyset (occurred_at, id); lọc theo ban tổ chức
CREATE INDEX idx_audit_logs_time ON audit_logs (occurred_at DESC, id DESC);
CREATE INDEX idx_audit_logs_organizer_time ON audit_logs (organizer_id, occurred_at DESC, id DESC);

CREATE TABLE audit_logs_default PARTITION OF audit_logs DEFAULT;

-- Bảng con tháng này tới 3 tháng sau (giờ UTC); job AuditLogPartitions giữ sẵn các tháng tiếp theo
DO $$
DECLARE
    month_start date := date_trunc('month', now() AT TIME ZONE 'UTC')::date;
    last_month  date := (date_trunc('month', now() AT TIME ZONE 'UTC') + interval '3 months')::date;
BEGIN
    WHILE month_start <= last_month LOOP
        EXECUTE format('CREATE TABLE %I PARTITION OF audit_logs FOR VALUES FROM (%L) TO (%L)',
                       'audit_logs_' || to_char(month_start, 'YYYY_MM'),
                       month_start::timestamp AT TIME ZONE 'UTC',
                       (month_start + interval '1 month')::timestamp AT TIME ZONE 'UTC');
        month_start := (month_start + interval '1 month')::date;
    END LOOP;
END $$;

CREATE FUNCTION audit_logs_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs chỉ được ghi thêm, không % được', TG_OP;
END
$$;

CREATE TRIGGER audit_logs_append_only BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_logs_append_only();
