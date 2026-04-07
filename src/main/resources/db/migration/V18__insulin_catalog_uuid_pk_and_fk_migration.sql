-- Move insulin_catalog primary key from code (VARCHAR) to id (UUID)
-- and migrate user_insulin_preferences foreign keys accordingly.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'insulin_catalog'
          AND column_name = 'id'
    ) THEN
        ALTER TABLE insulin_catalog
            ADD COLUMN id UUID;
    END IF;
END $$;

UPDATE insulin_catalog
SET id = gen_random_uuid()
WHERE id IS NULL;

ALTER TABLE insulin_catalog
ALTER COLUMN id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_insulin_catalog_code'
    ) THEN
        ALTER TABLE insulin_catalog
        ADD CONSTRAINT uk_insulin_catalog_code UNIQUE (code);
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
         AND tc.table_schema = kcu.table_schema
        WHERE tc.table_schema = 'public'
          AND tc.table_name = 'insulin_catalog'
          AND tc.constraint_type = 'PRIMARY KEY'
          AND kcu.column_name = 'code'
    ) THEN
        ALTER TABLE user_insulin_preferences
            DROP CONSTRAINT IF EXISTS user_insulin_preferences_rapid_insulin_code_fkey;
        ALTER TABLE user_insulin_preferences
            DROP CONSTRAINT IF EXISTS user_insulin_preferences_long_acting_insulin_code_fkey;

        ALTER TABLE insulin_catalog
            DROP CONSTRAINT IF EXISTS insulin_catalog_pkey;
        ALTER TABLE insulin_catalog
            ADD CONSTRAINT insulin_catalog_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'user_insulin_preferences'
          AND column_name = 'rapid_insulin_code'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'user_insulin_preferences'
          AND column_name = 'rapid_insulin_id'
    ) THEN
        ALTER TABLE user_insulin_preferences
            ADD COLUMN rapid_insulin_id UUID;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'user_insulin_preferences'
          AND column_name = 'long_acting_insulin_code'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'user_insulin_preferences'
          AND column_name = 'long_acting_insulin_id'
    ) THEN
        ALTER TABLE user_insulin_preferences
            ADD COLUMN long_acting_insulin_id UUID;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'user_insulin_preferences'
          AND column_name = 'rapid_insulin_code'
    ) THEN
        UPDATE user_insulin_preferences up
        SET rapid_insulin_id = ic.id
        FROM insulin_catalog ic
        WHERE up.rapid_insulin_id IS NULL
          AND up.rapid_insulin_code = ic.code;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'user_insulin_preferences'
          AND column_name = 'long_acting_insulin_code'
    ) THEN
        UPDATE user_insulin_preferences up
        SET long_acting_insulin_id = ic.id
        FROM insulin_catalog ic
        WHERE up.long_acting_insulin_id IS NULL
          AND up.long_acting_insulin_code = ic.code;
    END IF;
END $$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'user_insulin_preferences'
          AND column_name = 'rapid_insulin_code'
    ) OR EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'user_insulin_preferences'
          AND column_name = 'long_acting_insulin_code'
    ) THEN
        ALTER TABLE user_insulin_preferences
            ALTER COLUMN rapid_insulin_id SET NOT NULL;
        ALTER TABLE user_insulin_preferences
            ALTER COLUMN long_acting_insulin_id SET NOT NULL;

        ALTER TABLE user_insulin_preferences
            DROP CONSTRAINT IF EXISTS user_insulin_preferences_rapid_insulin_code_fkey;
        ALTER TABLE user_insulin_preferences
            DROP CONSTRAINT IF EXISTS user_insulin_preferences_long_acting_insulin_code_fkey;

        ALTER TABLE user_insulin_preferences
            ADD CONSTRAINT fk_user_insulin_preferences_rapid_insulin_id
                FOREIGN KEY (rapid_insulin_id) REFERENCES insulin_catalog(id);
        ALTER TABLE user_insulin_preferences
            ADD CONSTRAINT fk_user_insulin_preferences_long_acting_insulin_id
                FOREIGN KEY (long_acting_insulin_id) REFERENCES insulin_catalog(id);

        ALTER TABLE user_insulin_preferences
            DROP COLUMN IF EXISTS rapid_insulin_code;
        ALTER TABLE user_insulin_preferences
            DROP COLUMN IF EXISTS long_acting_insulin_code;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_user_insulin_preferences_rapid
ON user_insulin_preferences (rapid_insulin_id);
