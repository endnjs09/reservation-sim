CREATE TABLE sale_lifecycle (
  id                INT PRIMARY KEY CHECK (id = 1),
  started_at        TIMESTAMPTZ NOT NULL,
  sale_end_at       TIMESTAMPTZ NOT NULL,
  time_scale        INT NOT NULL CHECK (time_scale IN (1, 2, 4)),
  sale_duration_sec INT NOT NULL CHECK (sale_duration_sec > 0),
  ended_at          TIMESTAMPTZ
);

ALTER TABLE queue_tokens ADD COLUMN closed_reason VARCHAR(32);
