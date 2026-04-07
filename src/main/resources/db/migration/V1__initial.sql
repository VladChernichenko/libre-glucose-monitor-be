

CREATE TABLE IF NOT EXISTS users (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
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

CREATE TABLE IF NOT EXISTS carbs_entries (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
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

CREATE TABLE IF NOT EXISTS glucose_readings (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
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

CREATE TABLE IF NOT EXISTS insulin_doses (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    timestamp TIMESTAMP NOT NULL,
    units DOUBLE PRECISION NOT NULL,
    type VARCHAR(50) NOT NULL,
    note TEXT,
    meal_type VARCHAR(50),
    user_id UUID,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS user_configurations (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
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

-- Create COB settings table for user-specific carb on board configurations
CREATE TABLE IF NOT EXISTS cob_settings (
                              id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
                              user_id UUID NOT NULL,
                              carb_ratio DOUBLE PRECISION NOT NULL DEFAULT 2.0,
                              isf DOUBLE PRECISION NOT NULL DEFAULT 1.0,
                              carb_half_life INTEGER NOT NULL DEFAULT 45,
                              max_cob_duration INTEGER NOT NULL DEFAULT 240,
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                              CONSTRAINT fk_cob_settings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
                              CONSTRAINT uk_cob_settings_user UNIQUE (user_id)
);

-- Add indexes for better performance
CREATE INDEX IF NOT EXISTS idx_cob_settings_user_id ON cob_settings(user_id);
CREATE INDEX IF NOT EXISTS idx_cob_settings_created_at ON cob_settings(created_at);


-- Create notes table for user glucose notes
CREATE TABLE IF NOT EXISTS notes (
                       id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
                       user_id UUID NOT NULL,
                       timestamp TIMESTAMP NOT NULL,
                       carbs DOUBLE PRECISION NOT NULL,
                       insulin DOUBLE PRECISION NOT NULL,
                       meal VARCHAR(50) NOT NULL,
                       comment TEXT,
                       glucose_value DOUBLE PRECISION,
                       detailed_input TEXT,
                       insulin_dose TEXT,
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       CONSTRAINT fk_notes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Add indexes for better performance
CREATE INDEX IF NOT EXISTS idx_notes_user_timestamp ON notes(user_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_notes_timestamp ON notes(timestamp);
CREATE INDEX IF NOT EXISTS idx_notes_created_at ON notes(created_at);
