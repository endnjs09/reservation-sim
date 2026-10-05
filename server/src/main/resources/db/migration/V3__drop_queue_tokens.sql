DROP TABLE IF EXISTS queue_tokens;
ALTER TABLE reservations ADD COLUMN admission_kid UUID;
