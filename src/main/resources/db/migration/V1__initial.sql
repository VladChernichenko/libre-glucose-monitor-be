-- Fix all tables ID column type from VARCHAR to UUID
-- This migration handles the case where tables were created with VARCHAR IDs
-- For H2 compatibility, we'll use a simpler approach

-- Drop and recreate users table if it has wrong schema
DROP TABLE IF EXISTS users CASCADE;
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

-- Drop and recreate carbs_entries table if it has wrong schema
DROP TABLE IF EXISTS carbs_entries CASCADE;
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

-- Drop and recreate glucose_readings table if it has wrong schema
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

-- Drop and recreate insulin_doses table if it has wrong schema
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

-- Drop and recreate user_configurations table if it has wrong schema
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
