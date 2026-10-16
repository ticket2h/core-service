-- M1: tài khoản và ban tổ chức

CREATE TABLE organizers (
    id          uuid PRIMARY KEY,
    name        varchar(200) NOT NULL,
    created_at  timestamptz NOT NULL
);

CREATE TABLE users (
    id             uuid PRIMARY KEY,
    email          varchar(320) NOT NULL,
    password_hash  varchar(100) NOT NULL,
    full_name      varchar(200) NOT NULL,
    role           varchar(20) NOT NULL,
    organizer_id   uuid REFERENCES organizers (id),
    created_at     timestamptz NOT NULL,
    CONSTRAINT users_role_check CHECK (role IN ('CUSTOMER', 'ORGANIZER', 'STAFF', 'ADMIN')),
    -- Tài khoản ORGANIZER luôn thuộc một ban tổ chức
    CONSTRAINT users_organizer_role_check CHECK (role <> 'ORGANIZER' OR organizer_id IS NOT NULL),
    -- Email lưu dạng chữ thường để unique không phân biệt hoa thường
    CONSTRAINT users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT uq_users_email UNIQUE (email)
);
CREATE INDEX idx_users_organizer ON users (organizer_id) WHERE organizer_id IS NOT NULL;
