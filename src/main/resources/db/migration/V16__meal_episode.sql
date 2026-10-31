-- Meal episodes (spec 6.7): one row per meal note >= 10 g carbs once its 4 h window closed.
-- Situation + outcome + a food/context embedding for similarity search. Glucose in mmol/L.
CREATE TABLE IF NOT EXISTS meal_episode (
    id                     UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID             NOT NULL,
    note_id                UUID             NOT NULL,
    meal_at                TIMESTAMP        NOT NULL,
    local_minute_of_day    INT              NOT NULL,
    weekday                INT              NOT NULL,
    carbs                  DOUBLE PRECISION NOT NULL,
    fat                    DOUBLE PRECISION,
    protein                DOUBLE PRECISION,
    fiber                  DOUBLE PRECISION,
    gi                     DOUBLE PRECISION,
    food_text              TEXT,
    dose_units             DOUBLE PRECISION,
    pause_minutes          INT,
    start_glucose          DOUBLE PRECISION NOT NULL,
    start_trend            INT,
    iob                    DOUBLE PRECISION,
    cob                    DOUBLE PRECISION,
    activity_minutes_around INT,
    activity_type          VARCHAR(20),
    activity_before        BOOLEAN,
    site_zone              VARCHAR(16),
    context_tags           JSONB,
    peak                   DOUBLE PRECISION,
    peak_minute            INT,
    nadir                  DOUBLE PRECISION,
    nadir_minute           INT,
    g60                    DOUBLE PRECISION,
    g120                   DOUBLE PRECISION,
    g240                   DOUBLE PRECISION,
    minutes_below_3_9      INT,
    minutes_above_10       INT,
    hit60                  BOOLEAN,
    hit120                 BOOLEAN,
    contaminated           BOOLEAN          NOT NULL DEFAULT FALSE,
    embedding              vector(1536),
    created_at             TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT fk_meal_episode_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_meal_episode_note FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE,
    CONSTRAINT uq_meal_episode_note UNIQUE (note_id)
);

CREATE INDEX IF NOT EXISTS idx_meal_episode_user_meal_at ON meal_episode(user_id, meal_at);
CREATE INDEX IF NOT EXISTS idx_meal_episode_embedding
    ON meal_episode USING hnsw (embedding vector_cosine_ops);

COMMENT ON TABLE meal_episode IS 'Derived meal outcome record for similarity search; meal_at is user wall time.';
COMMENT ON COLUMN meal_episode.embedding IS 'text-embedding-3-small of the food/context rendering; NULL until the scheduler fills it.';
COMMENT ON COLUMN meal_episode.contaminated IS 'Other rapid dose or carbs inside the 4 h after (or 30 min before) the meal.';
