-- M4: báo cáo doanh thu cho ban tổ chức theo ngày, loại vé, kênh bán (ADR-016 trong docs/adr)

-- Kênh bán do client khai báo khi tạo đơn. Thêm cột có DEFAULT hằng số không phải ghi lại bảng (PostgreSQL 11+),
-- nên chạy tức thì kể cả khi bảng có 10 triệu đơn. Đơn cũ coi là bán qua web.
ALTER TABLE orders
    ADD COLUMN sales_channel varchar(20) NOT NULL DEFAULT 'WEB',
    ADD CONSTRAINT orders_sales_channel_check CHECK (sales_channel IN ('WEB', 'MOBILE_APP'));

-- Đơn vừa trả tiền, chờ job cộng vào bảng tổng hợp. Ghi trong CÙNG transaction với lúc đơn chuyển PAID, nên không
-- đơn nào bị sót; job xoá dòng ở đây và cộng vào bảng tổng hợp trong một câu lệnh, nên không đơn nào bị cộng hai lần.
CREATE TABLE sales_rollup_queue (
    order_id     uuid PRIMARY KEY REFERENCES orders (id),
    enqueued_at  timestamptz NOT NULL
);
CREATE INDEX idx_sales_rollup_queue_enqueued ON sales_rollup_queue (enqueued_at);

-- Vé và doanh thu đã bán, gom theo ngày (theo múi giờ của sự kiện), kênh bán, loại vé.
-- Một sự kiện 200.000 đơn chỉ còn vài trăm dòng ở đây.
CREATE TABLE sales_daily_items (
    event_id        uuid NOT NULL REFERENCES events (id),
    sale_date       date NOT NULL,
    sales_channel   varchar(20) NOT NULL,
    ticket_type_id  uuid NOT NULL REFERENCES ticket_types (id),
    tickets         bigint NOT NULL,
    revenue         numeric(16, 2) NOT NULL,
    CONSTRAINT pk_sales_daily_items PRIMARY KEY (event_id, sale_date, sales_channel, ticket_type_id),
    CONSTRAINT sales_daily_items_non_negative CHECK (tickets >= 0 AND revenue >= 0)
);

-- Số đơn theo ngày và kênh bán. Tách khỏi bảng trên vì một đơn có thể mua nhiều loại vé: cộng số đơn qua các loại
-- vé sẽ đếm một đơn nhiều lần. Ngày và kênh thì mỗi đơn chỉ có một.
CREATE TABLE sales_daily_orders (
    event_id       uuid NOT NULL REFERENCES events (id),
    sale_date      date NOT NULL,
    sales_channel  varchar(20) NOT NULL,
    orders         bigint NOT NULL,
    CONSTRAINT pk_sales_daily_orders PRIMARY KEY (event_id, sale_date, sales_channel),
    CONSTRAINT sales_daily_orders_non_negative CHECK (orders >= 0)
);
