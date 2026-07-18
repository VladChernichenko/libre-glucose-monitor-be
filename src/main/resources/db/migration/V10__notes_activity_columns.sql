-- Activity logging fields on notes (type = 'activity').
-- Added to V1 for fresh installs; this migration applies them to existing databases
-- where Flyway already ran V1 without these columns.

ALTER TABLE notes ADD COLUMN IF NOT EXISTS activity_type VARCHAR(20);
ALTER TABLE notes ADD COLUMN IF NOT EXISTS intensity VARCHAR(12);
ALTER TABLE notes ADD COLUMN IF NOT EXISTS duration_min INTEGER;
