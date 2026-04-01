-- Normalize legacy schemas where users.id/user_id were stored as VARCHAR.
-- This keeps all user identifiers UUID-consistent before adding new FK constraints.
DO $$
DECLARE
    col_record RECORD;
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'users'
          AND column_name = 'id'
          AND data_type IN ('character varying', 'text')
    ) THEN
        ALTER TABLE users
            ALTER COLUMN id TYPE UUID USING id::uuid;
        ALTER TABLE users
            ALTER COLUMN id SET DEFAULT gen_random_uuid();
    END IF;

    FOR col_record IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND column_name = 'user_id'
          AND data_type IN ('character varying', 'text')
    LOOP
        EXECUTE format(
            'ALTER TABLE %I ALTER COLUMN %I TYPE UUID USING %I::uuid',
            col_record.table_name,
            col_record.column_name,
            col_record.column_name
        );
    END LOOP;
END $$;

-- Add nightscout_chart_data table for storing 100 fixed rows per user
CREATE TABLE IF NOT EXISTS nightscout_chart_data (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    row_index INTEGER NOT NULL CHECK (row_index >= 0 AND row_index <= 99),
    nightscout_id VARCHAR(255),
    sgv INTEGER,
    date_timestamp BIGINT,
    date_string VARCHAR(255),
    trend INTEGER,
    direction VARCHAR(50),
    device VARCHAR(255),
    type VARCHAR(50),
    utc_offset INTEGER,
    sys_time VARCHAR(255),
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Ensure unique constraint: one row per user per index
    CONSTRAINT uk_nightscout_chart_data_user_row UNIQUE (user_id, row_index),
    
    -- Foreign key to users table
    CONSTRAINT fk_nightscout_chart_data_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Create index for faster lookups by user_id
CREATE INDEX IF NOT EXISTS idx_nightscout_chart_data_user_id ON nightscout_chart_data(user_id);

-- Create index for faster lookups by user_id and row_index
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'nightscout_chart_data'
          AND column_name = 'row_index'
    ) THEN
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_nightscout_chart_data_user_row ON nightscout_chart_data(user_id, row_index)';
    END IF;
END $$;

-- Create index for faster lookups by last_updated (for cleanup/maintenance)
CREATE INDEX IF NOT EXISTS idx_nightscout_chart_data_last_updated ON nightscout_chart_data(last_updated);

