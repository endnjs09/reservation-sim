ALTER TABLE seats DROP CONSTRAINT seats_status_check;
ALTER TABLE seats ADD CONSTRAINT seats_status_check
  CHECK (status IN ('AVAILABLE','HELD','PENDING_DEPOSIT','RETURN_PENDING','SOLD'));
ALTER TABLE reservations DROP CONSTRAINT reservations_status_check;
ALTER TABLE reservations ADD CONSTRAINT reservations_status_check
  CHECK (status IN ('HELD','CONFIRMING','CONFIRMED','EXPIRED','RELEASED','PAYMENT_FAILED',
                    'PENDING_DEPOSIT','DEPOSIT_EXPIRED','CANCELED'));
ALTER TABLE reservations ADD COLUMN payment_method VARCHAR(16) NOT NULL DEFAULT 'CARD'
  CHECK (payment_method IN ('CARD','DEPOSIT'));
ALTER TABLE reservations ADD COLUMN deposit_deadline TIMESTAMPTZ;
ALTER TABLE reservations ADD COLUMN seat_count INT NOT NULL DEFAULT 1 CHECK (seat_count > 0);

CREATE TABLE reservation_seats (
  reservation_id BIGINT NOT NULL REFERENCES reservations(id),
  seat_id BIGINT NOT NULL REFERENCES seats(id),
  released_at TIMESTAMPTZ,
  PRIMARY KEY (reservation_id,seat_id)
);
CREATE INDEX ix_reservation_seats_seat ON reservation_seats(seat_id);
INSERT INTO reservation_seats(reservation_id,seat_id,released_at)
SELECT id,seat_id,CASE WHEN status IN ('HELD','CONFIRMING','CONFIRMED') THEN NULL ELSE updated_at END
FROM reservations;
DROP INDEX IF EXISTS ux_res_seat_active;
-- Runtime BackstopManager applies the configured on/off policy after migration.

CREATE TABLE release_batches (
  id BIGSERIAL PRIMARY KEY,
  release_at TIMESTAMPTZ NOT NULL,
  released BOOLEAN NOT NULL DEFAULT FALSE,
  seat_count INT NOT NULL DEFAULT 0 CHECK (seat_count >= 0)
);
ALTER TABLE seats ADD COLUMN release_batch_id BIGINT REFERENCES release_batches(id);
