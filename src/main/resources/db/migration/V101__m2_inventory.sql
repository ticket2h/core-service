-- M2 (Bước 2): xem ticketrush-m2-m3-huong-dan.md

-- Vé GA: bộ đếm số lượng
ALTER TABLE ticket_types
    ADD COLUMN total_quantity integer,
    ADD COLUMN available_quantity integer;

-- Nếu M1 đã có dữ liệu GA, cập nhật total/available trước khi thêm ràng buộc này
ALTER TABLE ticket_types ADD CONSTRAINT ticket_types_quantity_check CHECK (
    (kind = 'GA' AND total_quantity IS NOT NULL
        AND available_quantity BETWEEN 0 AND total_quantity)
    OR (kind = 'SEATED' AND total_quantity IS NULL AND available_quantity IS NULL)
);

-- Ghế có số: trạng thái giữ/bán
ALTER TABLE seats
    ADD COLUMN status varchar(10) NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN order_id uuid REFERENCES orders (id),
    ADD COLUMN held_until timestamptz;

ALTER TABLE seats ADD CONSTRAINT seats_state_check CHECK (
    (status = 'AVAILABLE' AND order_id IS NULL AND held_until IS NULL)
    OR (status = 'HELD' AND order_id IS NOT NULL AND held_until IS NOT NULL)
    OR (status = 'SOLD' AND order_id IS NOT NULL AND held_until IS NULL)
);

CREATE INDEX idx_seats_order ON seats (order_id) WHERE order_id IS NOT NULL;
CREATE INDEX idx_seats_event_type ON seats (event_id, ticket_type_id);
