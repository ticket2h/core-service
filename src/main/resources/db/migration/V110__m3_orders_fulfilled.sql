-- M3 (Bước 13): xem ticketrush-m2-m3-huong-dan.md

ALTER TABLE orders DROP CONSTRAINT orders_status_check;
ALTER TABLE orders ADD CONSTRAINT orders_status_check
    CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'FULFILLED', 'EXPIRED', 'CANCELLED'));
ALTER TABLE orders ADD COLUMN fulfilled_at timestamptz;
ALTER TABLE orders ADD CONSTRAINT orders_fulfilled_has_time
    CHECK (status <> 'FULFILLED' OR (fulfilled_at IS NOT NULL AND paid_at IS NOT NULL));
