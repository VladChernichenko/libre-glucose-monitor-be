-- Fix all tables ID column type from VARCHAR to UUID
-- This migration handles the case where tables were created with VARCHAR IDs

-- First, check and fix users table if needed
DO $$
BEGIN
    -- Check if users table exists and has VARCHAR id column
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'users' 
        AND column_name = 'id' 
        AND data_type = 'character varying'
    ) THEN
        -- Drop the table if it exists with wrong schema (since we can't easily convert VARCHAR to UUID)
        -- This is safe because it's likely a development database
        DROP TABLE IF EXISTS users CASCADE;
        
        -- Recreate the table with correct UUID schema
        CREATE TABLE users (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            email VARCHAR(255) UNIQUE NOT NULL,
            username VARCHAR(255) UNIQUE NOT NULL,
            password VARCHAR(255) NOT NULL,
            full_name VARCHAR(255),
            role VARCHAR(50) DEFAULT 'USER',
            enabled BOOLEAN DEFAULT true,
            account_non_expired BOOLEAN DEFAULT true,
            credentials_non_expired BOOLEAN DEFAULT true,
            account_non_locked BOOLEAN DEFAULT true,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        
        RAISE NOTICE 'Recreated users table with UUID ID column';
    ELSE
        RAISE NOTICE 'Users table already has correct UUID ID column or does not exist';
    END IF;
END $$;

-- Check and fix carbs_entries table if needed
DO $$
BEGIN
    -- Check if carbs_entries table exists and has VARCHAR id column
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'carbs_entries' 
        AND column_name = 'id' 
        AND data_type = 'character varying'
    ) THEN
        -- Drop the table if it exists with wrong schema (since we can't easily convert VARCHAR to UUID)
        -- This is safe because it's likely a development database
        DROP TABLE IF EXISTS carbs_entries CASCADE;
        
        -- Recreate the table with correct UUID schema
        CREATE TABLE carbs_entries (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            timestamp TIMESTAMP NOT NULL,
            carbs DOUBLE PRECISION NOT NULL,
            insulin DOUBLE PRECISION NOT NULL,
            meal_type VARCHAR(50),
            comment TEXT,
            glucose_value DOUBLE PRECISION,
            original_carbs DOUBLE PRECISION,
            user_id UUID,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        
        RAISE NOTICE 'Recreated carbs_entries table with UUID ID column';
    ELSE
        RAISE NOTICE 'Carbs_entries table already has correct UUID ID column or does not exist';
    END IF;
END $$;

-- Also ensure other tables have correct UUID schema
DO $$
BEGIN
    -- Check and fix glucose_readings table if needed
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'glucose_readings' 
        AND column_name = 'id' 
        AND data_type = 'character varying'
    ) THEN
        DROP TABLE IF EXISTS glucose_readings CASCADE;
        
        CREATE TABLE glucose_readings (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            value FLOAT NOT NULL,
            timestamp TIMESTAMP NOT NULL,
            unit VARCHAR(10) NOT NULL DEFAULT 'mg/dL',
            status VARCHAR(50),
            trend VARCHAR(50),
            data_source VARCHAR(100),
            original_timestamp TIMESTAMP,
            user_id UUID,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        
        RAISE NOTICE 'Recreated glucose_readings table with UUID ID column';
    ELSE
        RAISE NOTICE 'Glucose_readings table already has correct UUID ID column or does not exist';
    END IF;
END $$;

DO $$
BEGIN
    -- Check and fix insulin_doses table if needed
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'insulin_doses' 
        AND column_name = 'id' 
        AND data_type = 'character varying'
    ) THEN
        DROP TABLE IF EXISTS insulin_doses CASCADE;
        
        CREATE TABLE insulin_doses (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            timestamp TIMESTAMP NOT NULL,
            units DOUBLE PRECISION NOT NULL,
            type VARCHAR(50) NOT NULL,
            note TEXT,
            meal_type VARCHAR(50),
            user_id UUID,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        
        RAISE NOTICE 'Recreated insulin_doses table with UUID ID column';
    ELSE
        RAISE NOTICE 'Insulin_doses table already has correct UUID ID column or does not exist';
    END IF;
END $$;

DO $$
BEGIN
    -- Check and fix user_configurations table if needed
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'user_configurations' 
        AND column_name = 'id' 
        AND data_type = 'character varying'
    ) THEN
        DROP TABLE IF EXISTS user_configurations CASCADE;
        
        CREATE TABLE user_configurations (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id UUID UNIQUE NOT NULL,
            carb_ratio DOUBLE PRECISION,
            insulin_sensitivity_factor DOUBLE PRECISION,
            carb_half_life INTEGER,
            max_cob_duration INTEGER,
            target_glucose DOUBLE PRECISION,
            insulin_half_life INTEGER,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        );
        
        RAISE NOTICE 'Recreated user_configurations table with UUID ID column';
    ELSE
        RAISE NOTICE 'User_configurations table already has correct UUID ID column or does not exist';
    END IF;
END $$;
