-- Create user_data_source_config table for storing user-specific data source configurations
CREATE TABLE IF NOT EXISTS user_data_source_config (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    data_source VARCHAR(50) NOT NULL CHECK (data_source IN ('NIGHTSCOUT', 'LIBRE_LINK_UP')),
    
    -- Nightscout configuration fields
    nightscout_url VARCHAR(500),
    nightscout_api_secret VARCHAR(255),
    nightscout_api_token VARCHAR(255),
    
    -- LibreLinkUp configuration fields
    libre_email VARCHAR(255),
    libre_password VARCHAR(255),
    libre_patient_id VARCHAR(255),
    
    is_active BOOLEAN NOT NULL DEFAULT true,
    last_used TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for better performance
CREATE INDEX IF NOT EXISTS idx_user_data_source_config_user_id ON user_data_source_config(user_id);
CREATE INDEX IF NOT EXISTS idx_user_data_source_config_user_source ON user_data_source_config(user_id, data_source);
CREATE INDEX IF NOT EXISTS idx_user_data_source_config_active ON user_data_source_config(user_id, data_source, is_active);
CREATE INDEX IF NOT EXISTS idx_user_data_source_config_last_used ON user_data_source_config(last_used);

-- Create unique constraint to ensure only one active config per user per data source type
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_data_source_config_unique_active 
ON user_data_source_config(user_id, data_source) 
WHERE is_active = true;

-- Add comments for documentation
COMMENT ON TABLE user_data_source_config IS 'Stores user-specific data source configurations for Nightscout and LibreLinkUp';
COMMENT ON COLUMN user_data_source_config.data_source IS 'Type of data source: NIGHTSCOUT or LIBRE_LINK_UP';
COMMENT ON COLUMN user_data_source_config.nightscout_url IS 'Nightscout instance URL';
COMMENT ON COLUMN user_data_source_config.nightscout_api_secret IS 'Nightscout API secret for authentication';
COMMENT ON COLUMN user_data_source_config.nightscout_api_token IS 'Nightscout API token for authentication';
COMMENT ON COLUMN user_data_source_config.libre_email IS 'LibreLinkUp email address';
COMMENT ON COLUMN user_data_source_config.libre_password IS 'LibreLinkUp password';
COMMENT ON COLUMN user_data_source_config.libre_patient_id IS 'LibreLinkUp patient ID';
COMMENT ON COLUMN user_data_source_config.is_active IS 'Whether this configuration is currently active';
COMMENT ON COLUMN user_data_source_config.last_used IS 'Timestamp when this configuration was last used';
