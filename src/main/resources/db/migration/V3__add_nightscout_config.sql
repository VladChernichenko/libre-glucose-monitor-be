-- Add nightscout_config table for storing user-specific Nightscout settings
CREATE TABLE nightscout_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE,
    nightscout_url VARCHAR(500) NOT NULL,
    api_secret VARCHAR(255),
    api_token VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT true,
    last_used TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    
    -- Foreign key to users table
    CONSTRAINT fk_nightscout_config_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Create index for faster lookups by user_id
CREATE INDEX idx_nightscout_config_user_id ON nightscout_config(user_id);

-- Create index for faster lookups by is_active
CREATE INDEX idx_nightscout_config_active ON nightscout_config(is_active);

-- Create index for faster lookups by last_used (for cleanup/maintenance)
CREATE INDEX idx_nightscout_config_last_used ON nightscout_config(last_used);
