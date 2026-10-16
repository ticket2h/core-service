-- M2 (Bước 2): xem ticketrush-m2-m3-huong-dan.md

ALTER TABLE events
    ADD COLUMN max_tickets_per_user integer NOT NULL DEFAULT 4,
    ADD CONSTRAINT events_max_tickets_positive CHECK (max_tickets_per_user > 0);

CREATE TABLE orders (
    id               uuid PRIMARY KEY,
    user_id          uuid NOT NULL REFERENCES users (id),
    event_id         uuid NOT NULL REFERENCES events (id),
    status           varchar(20) NOT NULL,
    total_amount     numeric(14, 2) NOT NULL,
    currency         char(3) NOT NULL DEFAULT 'VND',
    idempotency_key  varchar(64) NOT NULL,
    request_hash     char(64) NOT NULL,
    expires_at       timestamptz NOT NULL,
    paid_at          timestamptz,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT orders_status_check
        CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT orders_total_non_negative CHECK (total_amount >= 0),
    CONSTRAINT orders_paid_has_time CHECK (status <> 'PAID' OR paid_at IS NOT NULL),
    CONSTRAINT uq_orders_user_idempotency_key UNIQUE (user_id, idempotency_key)
);

-- Job dọn đơn hết hạn chỉ quét đơn đang chờ thanh toán
CREATE INDEX idx_orders_pending_expiry ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';
CREATE INDEX idx_orders_user_created ON orders (user_id, created_at DESC);

CREATE TABLE order_items (
    id              uuid PRIMARY KEY,
    order_id        uuid NOT NULL REFERENCES orders (id),
    ticket_type_id  uuid NOT NULL REFERENCES ticket_types (id),
    seat_id         uuid REFERENCES seats (id),
    quantity        integer NOT NULL,
    unit_price      numeric(14, 2) NOT NULL,
    CONSTRAINT order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_items_seat_quantity_one CHECK (seat_id IS NULL OR quantity = 1)
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

CREATE TABLE purchase_quotas (
    user_id   uuid NOT NULL REFERENCES users (id),
    event_id  uuid NOT NULL REFERENCES events (id),
    reserved  integer NOT NULL,
    PRIMARY KEY (user_id, event_id),
    CONSTRAINT purchase_quotas_non_negative CHECK (reserved >= 0)
);
