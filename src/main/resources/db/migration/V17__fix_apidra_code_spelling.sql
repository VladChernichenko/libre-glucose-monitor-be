-- Correct legacy typo in insulin catalog code: APRIDRA -> APIDRA.
-- Keep this migration even though V7 is corrected, to repair existing databases.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'user_insulin_preferences'
          AND column_name = 'rapid_insulin_code'
    ) THEN
        UPDATE user_insulin_preferences
        SET rapid_insulin_code = 'APIDRA'
        WHERE rapid_insulin_code = 'APRIDRA';
    END IF;
END $$;

UPDATE insulin_catalog
SET code = 'APIDRA'
WHERE code = 'APRIDRA';
