ALTER TABLE release_batches ADD COLUMN released_at TIMESTAMPTZ;
ALTER TABLE release_batches ADD COLUMN reopened BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX ux_release_batch_open ON release_batches((TRUE)) WHERE released=FALSE;
ALTER TABLE sale_lifecycle ADD COLUMN ever_zero BOOLEAN NOT NULL DEFAULT FALSE;
