-- Standardize tables to use surrogate UUID primary key `id` and keep `user_id` as unique FK.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name
         AND tc.table_schema = kcu.table_schema
        WHERE tc.table_schema = 'public'
          AND tc.table_name = 'user_insulin_preferences'
          AND tc.constraint_type = 'PRIMARY KEY'
          AND kcu.column_name = 'user_id'
    ) THEN
        ALTER TABLE user_insulin_preferences
            ADD COLUMN id UUID;
        UPDATE user_insulin_preferences
        SET id = gen_random_uuid()
        WHERE id IS NULL;
        ALTER TABLE user_insulin_preferences
            ALTER COLUMN id SET NOT NULL;
        ALTER TABLE user_insulin_preferences
            DROP CONSTRAINT IF EXISTS user_insulin_preferences_pkey;
        ALTER TABLE user_insulin_preferences
            ADD CONSTRAINT user_insulin_preferences_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_user_insulin_preferences_user_id'
    ) THEN
        ALTER TABLE user_insulin_preferences
        ADD CONSTRAINT uk_user_insulin_preferences_user_id UNIQUE (user_id);
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
          AND tc.table_name = 'user_glucose_sync_state'
          AND tc.constraint_type = 'PRIMARY KEY'
          AND kcu.column_name = 'user_id'
    ) THEN
        ALTER TABLE user_glucose_sync_state
            ADD COLUMN id UUID;
        UPDATE user_glucose_sync_state
        SET id = gen_random_uuid()
        WHERE id IS NULL;
        ALTER TABLE user_glucose_sync_state
            ALTER COLUMN id SET NOT NULL;
        ALTER TABLE user_glucose_sync_state
            DROP CONSTRAINT IF EXISTS user_glucose_sync_state_pkey;
        ALTER TABLE user_glucose_sync_state
            ADD CONSTRAINT user_glucose_sync_state_pkey PRIMARY KEY (id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uk_user_glucose_sync_state_user_id'
    ) THEN
        ALTER TABLE user_glucose_sync_state
        ADD CONSTRAINT uk_user_glucose_sync_state_user_id UNIQUE (user_id);
    END IF;
END $$;
