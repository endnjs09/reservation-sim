CREATE TABLE seats (
  id                      BIGINT PRIMARY KEY,
  label                   VARCHAR(10)  NOT NULL UNIQUE,
  row_index               INT          NOT NULL,
  col_index               INT          NOT NULL,
  grade                   VARCHAR(16)  NOT NULL,
  price                   INT          NOT NULL,
  status                  VARCHAR(16)  NOT NULL CHECK (status IN ('AVAILABLE','HELD','SOLD')),
  current_reservation_id  BIGINT,
  version                 BIGINT       NOT NULL DEFAULT 0,
  updated_at              TIMESTAMPTZ  NOT NULL
);

CREATE TABLE reservations (
  id                BIGSERIAL PRIMARY KEY,
  seat_id           BIGINT       NOT NULL REFERENCES seats(id),
  user_id           VARCHAR(64)  NOT NULL,
  status            VARCHAR(20)  NOT NULL CHECK (status IN ('HELD','CONFIRMING','CONFIRMED','EXPIRED','RELEASED','PAYMENT_FAILED')),
  hold_expires_at   TIMESTAMPTZ  NOT NULL,
  confirm_deadline  TIMESTAMPTZ,
  idempotency_key   VARCHAR(64)  NOT NULL,
  created_at        TIMESTAMPTZ  NOT NULL,
  updated_at        TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX ux_reservations_idempotency ON reservations(idempotency_key);
CREATE INDEX ix_reservations_status_expiry ON reservations(status, hold_expires_at);
CREATE INDEX ix_reservations_user ON reservations(user_id);

CREATE TABLE payments (
  id              UUID PRIMARY KEY,                 -- = orderId
  reservation_id  BIGINT       NOT NULL UNIQUE REFERENCES reservations(id),
  amount          INT          NOT NULL,
  status          VARCHAR(16)  NOT NULL CHECK (status IN ('REQUESTED','APPROVED','FAILED','CANCELED')),
  payment_key     VARCHAR(64),
  fail_reason     VARCHAR(64),
  created_at      TIMESTAMPTZ  NOT NULL,
  updated_at      TIMESTAMPTZ  NOT NULL
);

CREATE TABLE queue_tokens (
  token                 UUID PRIMARY KEY,
  user_id               VARCHAR(64) NOT NULL,
  seq                   BIGSERIAL   NOT NULL UNIQUE,
  status                VARCHAR(16) NOT NULL CHECK (status IN ('WAITING','ADMITTED','COMPLETED','LEFT','EXPIRED','CLOSED')),
  created_at            TIMESTAMPTZ NOT NULL,
  admitted_at           TIMESTAMPTZ,
  admission_expires_at  TIMESTAMPTZ,
  ended_at              TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_queue_user_active ON queue_tokens(user_id) WHERE status IN ('WAITING','ADMITTED');
CREATE INDEX ix_queue_status_seq ON queue_tokens(status, seq);
