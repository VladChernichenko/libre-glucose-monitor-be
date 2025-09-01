-- Fix users table schema migration
-- This script handles the transition from the old schema to the new one

-- First, update existing records to have a username (use email as username if username is null)
UPDATE users 
SET username = email 
WHERE username IS NULL;

-- Now add the NOT NULL constraint to username
ALTER TABLE users ALTER COLUMN username SET NOT NULL;

-- Add unique constraint to username
ALTER TABLE users ADD CONSTRAINT uk_users_username UNIQUE (username);

-- Update password column name if needed (from password_hash to password)
-- This is safe to run multiple times
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns 
               WHERE table_name = 'users' AND column_name = 'password_hash') THEN
        ALTER TABLE users RENAME COLUMN password_hash TO password;
    END IF;
END $$;

-- Add missing columns if they don't exist
DO $$
BEGIN
    -- Add role column if it doesn't exist
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'users' AND column_name = 'role') THEN
        ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';
    END IF;
    
    -- Add enabled column if it doesn't exist
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'users' AND column_name = 'enabled') THEN
        ALTER TABLE users ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
    END IF;
    
    -- Add account_non_expired column if it doesn't exist
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'users' AND column_name = 'account_non_expired') THEN
        ALTER TABLE users ADD COLUMN account_non_expired BOOLEAN NOT NULL DEFAULT TRUE;
    END IF;
    
    -- Add credentials_non_expired column if it doesn't exist
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'users' AND column_name = 'credentials_non_expired') THEN
        ALTER TABLE users ADD COLUMN credentials_non_expired BOOLEAN NOT NULL DEFAULT TRUE;
    END IF;
    
    -- Add account_non_locked column if it doesn't exist
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'users' AND column_name = 'account_non_locked') THEN
        ALTER TABLE users ADD COLUMN account_non_locked BOOLEAN NOT NULL DEFAULT TRUE;
    END IF;
    
    -- Add created_at column if it doesn't exist
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'users' AND column_name = 'created_at') THEN
        ALTER TABLE users ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
    END IF;
    
    -- Add updated_at column if it doesn't exist
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns 
                   WHERE table_name = 'users' AND column_name = 'updated_at') THEN
        ALTER TABLE users ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
    END IF;
END $$;
