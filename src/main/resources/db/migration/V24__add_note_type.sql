-- Note category discriminator. Long-acting (basal) insulin notes are tagged 'long_acting'
-- so their dose (stored in the existing `insulin` column) can be excluded from rapid-acting
-- (bolus) IOB, prediction, bolus-to-meal timing, and over-injection math. Existing rows are
-- ordinary meal/correction/bolus notes → 'normal'.
ALTER TABLE notes ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'normal';

-- Speeds up the common "recent notes by user" scans that now also branch on type.
CREATE INDEX IF NOT EXISTS idx_notes_user_type ON notes (user_id, type);
