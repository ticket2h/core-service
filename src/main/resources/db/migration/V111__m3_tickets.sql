-- M3 (Bước 13): xem ticketrush-m2-m3-huong-dan.md

CREATE TABLE IF NOT EXISTS event_staff (
    event_id  uuid NOT NULL REFERENCES events (id),
    user_id   uuid NOT NULL REFERENCES users (id),
    PRIMARY KEY (event_id, user_id)
);

CREATE TABLE tickets (
    id               uuid PRIMARY KEY,
    order_id         uuid NOT NULL REFERENCES orders (id),
    order_item_id    uuid NOT NULL REFERENCES order_items (id),
    item_seq         smallint NOT NULL,
    event_id         uuid NOT NULL REFERENCES events (id),
    ticket_type_id   uuid NOT NULL REFERENCES ticket_types (id),
    seat_id          uuid REFERENCES seats (id),
    owner_user_id    uuid NOT NULL REFERENCES users (id),
    status           varchar(10) NOT NULL,
    token_version    integer NOT NULL DEFAULT 1,
    issued_at        timestamptz NOT NULL,
    checked_in_at    timestamptz,
    checked_in_gate  varchar(50),
    checked_in_by    uuid REFERENCES users (id),
    CONSTRAINT tickets_status_check CHECK (status IN ('VALID', 'USED', 'VOID')),
    CONSTRAINT tickets_item_seq_positive CHECK (item_seq > 0),
    CONSTRAINT tickets_token_version_positive CHECK (token_version > 0),
    CONSTRAINT tickets_used_has_checkin CHECK (status <> 'USED' OR checked_in_at IS NOT NULL),
    -- Phát vé lặp lại không tạo thêm vé
    CONSTRAINT uq_tickets_order_item_seq UNIQUE (order_item_id, item_seq)
);

-- Một ghế chỉ có tối đa một vé còn hiệu lực
CREATE UNIQUE INDEX uq_tickets_active_seat ON tickets (seat_id)
    WHERE seat_id IS NOT NULL AND status <> 'VOID';
CREATE INDEX idx_tickets_owner ON tickets (owner_user_id, issued_at DESC);
CREATE INDEX idx_tickets_order ON tickets (order_id);

CREATE TABLE ticket_transfers (
    id                 uuid PRIMARY KEY,
    ticket_id          uuid NOT NULL REFERENCES tickets (id),
    from_user_id       uuid NOT NULL REFERENCES users (id),
    to_user_id         uuid NOT NULL REFERENCES users (id),
    new_token_version  integer NOT NULL,
    transferred_at     timestamptz NOT NULL,
    CONSTRAINT ticket_transfers_different_users CHECK (from_user_id <> to_user_id)
);
CREATE INDEX idx_ticket_transfers_ticket ON ticket_transfers (ticket_id);

-- Ghi mọi lượt quét, cả thành công lẫn thất bại. M4 sẽ chuyển sang bảng partition.
CREATE TABLE checkin_logs (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id      uuid,
    event_id       uuid,
    staff_user_id  uuid NOT NULL,
    gate           varchar(50) NOT NULL,
    result         varchar(30) NOT NULL,
    scanned_at     timestamptz NOT NULL
);
CREATE INDEX idx_checkin_logs_event_time ON checkin_logs (event_id, scanned_at);
