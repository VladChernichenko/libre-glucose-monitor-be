-- Reference insulin PK parameters (literature / product summaries / common DIY pump defaults; approximate).
CREATE TABLE IF NOT EXISTS insulin_catalog (
    id                   UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    code                 VARCHAR(32) UNIQUE NOT NULL,
    category             VARCHAR(20)  NOT NULL CHECK (category IN ('RAPID', 'LONG_ACTING')),
    display_name         VARCHAR(128) NOT NULL,
    peak_minutes         INTEGER,
    dia_hours            DOUBLE PRECISION NOT NULL,
    half_life_minutes    DOUBLE PRECISION NOT NULL,
    onset_minutes        INTEGER,
    description          TEXT
);

INSERT INTO insulin_catalog (code, category, display_name, peak_minutes, dia_hours, half_life_minutes, onset_minutes, description) VALUES
('FIASP', 'RAPID', 'Fiasp (faster aspart)', 55, 4.5, 52, 7,
 'Ultra-rapid bolus: OpenAPS-style exponential IOB (peak ~55 min, DIA ~4.5 h). Elimination half-life order of magnitude ~50Р Р†Р вЂљРІР‚Сљ60 min (approx.).'),
('APIDRA', 'RAPID', 'Apidra (insulin glulisine)', 75, 4.0, 45, 20,
 'Rapid bolus: OpenAPS rapid-acting peak 75 min, DIA ~4 h. Glulisine half-life commonly cited ~40Р Р†Р вЂљРІР‚Сљ50 min (approx.).'),
('TRESIBA', 'LONG_ACTING', 'Tresiba (insulin degludec)', NULL, 42.0, 1500, 60,
 'Basal: terminal half-life ~25 h; clinical duration up to ~42 h (summary of product characteristics). Stored for profile; bolus IOB uses rapid insulin only.'),
('LANTUS', 'LONG_ACTING', 'Lantus (insulin glargine 100 U/mL)', NULL, 24.0, 720, 90,
 'Basal: ~24 h effect; half-life order of magnitude ~12 h (approx.). Stored for profile; bolus IOB uses rapid insulin only.')
ON CONFLICT (code) DO UPDATE SET
    category = EXCLUDED.category,
    display_name = EXCLUDED.display_name,
    peak_minutes = EXCLUDED.peak_minutes,
    dia_hours = EXCLUDED.dia_hours,
    half_life_minutes = EXCLUDED.half_life_minutes,
    onset_minutes = EXCLUDED.onset_minutes,
    description = EXCLUDED.description;

CREATE TABLE IF NOT EXISTS user_insulin_preferences (
    id                         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    user_id                    UUID NOT NULL UNIQUE,
    rapid_insulin_id           UUID NOT NULL REFERENCES insulin_catalog (id),
    long_acting_insulin_id     UUID NOT NULL REFERENCES insulin_catalog (id),
    created_at                 TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_insulin_preferences_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_insulin_preferences_rapid ON user_insulin_preferences (rapid_insulin_id);
