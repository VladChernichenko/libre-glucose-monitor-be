-- Create COB settings table for user-specific carb on board configurations
CREATE TABLE cob_settings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
CREATE INDEX idx_cob_settings_user_id ON cob_settings(user_id);
CREATE INDEX idx_cob_settings_created_at ON cob_settings(created_at);
