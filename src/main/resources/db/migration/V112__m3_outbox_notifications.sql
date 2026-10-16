-- M3 (Bước 13): xem ticketrush-m2-m3-huong-dan.md

CREATE TABLE outbox_events (
    id              uuid PRIMARY KEY,
    aggregate_type  varchar(50) NOT NULL,
    aggregate_id    uuid NOT NULL,
    event_type      varchar(100) NOT NULL,
    topic           varchar(100) NOT NULL,
    payload         jsonb NOT NULL,
    created_at      timestamptz NOT NULL,
    published_at    timestamptz,
    attempts        integer NOT NULL DEFAULT 0,
    last_error      text
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

CREATE TABLE notification_deliveries (
    id          uuid PRIMARY KEY,
    dedupe_key  varchar(200) NOT NULL,
    channel     varchar(20) NOT NULL,
    recipient   varchar(320) NOT NULL,
    status      varchar(20) NOT NULL,
    attempts    integer NOT NULL DEFAULT 0,
    sent_at     timestamptz,
    created_at  timestamptz NOT NULL,
    CONSTRAINT notification_deliveries_status_check CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT uq_notification_dedupe UNIQUE (dedupe_key)
);
