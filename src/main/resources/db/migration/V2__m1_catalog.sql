-- M1: địa điểm, sự kiện, loại vé, sơ đồ ghế

-- Tìm kiếm chuỗi con trên search_text (đã bỏ dấu ở phía Java)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE venues (
    id            uuid PRIMARY KEY,
    organizer_id  uuid NOT NULL REFERENCES organizers (id),
    name          varchar(200) NOT NULL,
    address       varchar(500) NOT NULL,
    city          varchar(100) NOT NULL,
    created_at    timestamptz NOT NULL
);
CREATE INDEX idx_venues_organizer ON venues (organizer_id);

CREATE TABLE events (
    id              uuid PRIMARY KEY,
    organizer_id    uuid NOT NULL REFERENCES organizers (id),
    venue_id        uuid NOT NULL REFERENCES venues (id),
    title           varchar(200) NOT NULL,
    description     text NOT NULL DEFAULT '',
    time_zone       varchar(50) NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    starts_at       timestamptz NOT NULL,
    sale_opens_at   timestamptz NOT NULL,
    sale_closes_at  timestamptz NOT NULL,
    status          varchar(20) NOT NULL,
    -- title + tên địa điểm + thành phố, chữ thường, bỏ dấu tiếng Việt (VietnameseText.normalize)
    search_text     text NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT events_status_check CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT events_sale_window_check CHECK (sale_opens_at < sale_closes_at AND sale_closes_at <= starts_at)
);
CREATE INDEX idx_events_organizer ON events (organizer_id);
CREATE INDEX idx_events_published_starts ON events (starts_at) WHERE status = 'PUBLISHED';
CREATE INDEX idx_events_search_trgm ON events USING gin (search_text gin_trgm_ops);

CREATE TABLE ticket_types (
    id          uuid PRIMARY KEY,
    event_id    uuid NOT NULL REFERENCES events (id),
    name        varchar(100) NOT NULL,
    kind        varchar(10) NOT NULL,
    price       numeric(14, 2) NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT ticket_types_kind_check CHECK (kind IN ('SEATED', 'GA')),
    CONSTRAINT ticket_types_price_non_negative CHECK (price >= 0),
    CONSTRAINT uq_ticket_types_event_name UNIQUE (event_id, name),
    -- Đích cho khoá ngoại (ticket_type_id, event_id) của seats
    CONSTRAINT uq_ticket_types_id_event UNIQUE (id, event_id)
);
CREATE INDEX idx_ticket_types_event ON ticket_types (event_id);

CREATE TABLE seats (
    id              uuid PRIMARY KEY,
    event_id        uuid NOT NULL REFERENCES events (id),
    ticket_type_id  uuid NOT NULL,
    section         varchar(50) NOT NULL,
    row_label       varchar(10) NOT NULL,
    seat_number     integer NOT NULL,
    CONSTRAINT seats_number_positive CHECK (seat_number > 0),
    CONSTRAINT uq_seats_position UNIQUE (event_id, section, row_label, seat_number),
    -- Ghế chỉ thuộc loại vé của chính sự kiện đó
    CONSTRAINT fk_seats_ticket_type_same_event FOREIGN KEY (ticket_type_id, event_id)
        REFERENCES ticket_types (id, event_id)
);
