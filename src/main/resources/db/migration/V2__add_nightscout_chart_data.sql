-- Add nightscout_chart_data table for storing 100 fixed rows per user
CREATE TABLE nightscout_chart_data (
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
CREATE INDEX idx_nightscout_chart_data_user_id ON nightscout_chart_data(user_id);

-- Create index for faster lookups by user_id and row_index
CREATE INDEX idx_nightscout_chart_data_user_row ON nightscout_chart_data(user_id, row_index);

-- Create index for faster lookups by last_updated (for cleanup/maintenance)
CREATE INDEX idx_nightscout_chart_data_last_updated ON nightscout_chart_data(last_updated);

