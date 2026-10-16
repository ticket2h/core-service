-- M2 (Bước 2): xem ticketrush-m2-m3-huong-dan.md

CREATE TABLE payments (
    id                      uuid PRIMARY KEY,          -- cũng là merchantRef gửi sang cổng
    order_id                uuid NOT NULL REFERENCES orders (id),
    provider                varchar(20) NOT NULL,
    status                  varchar(20) NOT NULL,
    amount                  numeric(14, 2) NOT NULL,
    currency                char(3) NOT NULL,
    gateway_payment_id      varchar(64),
    gateway_transaction_id  varchar(64),
    payment_url             text,
    expires_at              timestamptz NOT NULL,
    succeeded_at            timestamptz,
    refund_reason           varchar(100),
    refund_attempts         integer NOT NULL DEFAULT 0,
    next_refund_attempt_at  timestamptz,
    last_error              text,
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    CONSTRAINT payments_status_check CHECK (status IN
        ('INITIATED', 'SUCCEEDED', 'FAILED', 'AMOUNT_MISMATCH', 'REFUND_PENDING', 'REFUNDED')),
    CONSTRAINT uq_payments_gateway_payment UNIQUE (provider, gateway_payment_id),
    CONSTRAINT uq_payments_gateway_transaction UNIQUE (provider, gateway_transaction_id)
);
CREATE INDEX idx_payments_order ON payments (order_id);
CREATE INDEX idx_payments_refund_due ON payments (next_refund_attempt_at)
    WHERE status = 'REFUND_PENDING';

-- Lưu nguyên văn mọi webhook nhận được, kể cả sai chữ ký, để điều tra khi có sự cố
CREATE TABLE payment_webhook_logs (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    provider           varchar(20) NOT NULL,
    provider_event_id  varchar(64),
    signature_valid    boolean NOT NULL,
    body               text NOT NULL,
    result             varchar(40),
    received_at        timestamptz NOT NULL
);
CREATE INDEX idx_webhook_logs_received ON payment_webhook_logs (received_at);
