-- Create notes table for user glucose notes
CREATE TABLE notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
CREATE INDEX idx_notes_user_timestamp ON notes(user_id, timestamp);
CREATE INDEX idx_notes_timestamp ON notes(timestamp);
CREATE INDEX idx_notes_created_at ON notes(created_at);