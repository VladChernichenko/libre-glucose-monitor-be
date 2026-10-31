-- Experiments: controlled ISF / Carb Ratio determination protocols.
-- Each experiment has zero or more glucose readings collected during the run.

CREATE TABLE IF NOT EXISTS experiments (
    id                   UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID             NOT NULL,
    type                 VARCHAR(30)      NOT NULL,
    status               VARCHAR(20)      NOT NULL DEFAULT 'PENDING',
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    grams_consumed       DOUBLE PRECISION,
    units_injected       DOUBLE PRECISION,
    computed_isf         DOUBLE PRECISION,
    computed_carb_ratio  DOUBLE PRECISION,
    is_stable            BOOLEAN,
    result_notes         TEXT,
    created_at           TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_experiments_user   FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_experiment_type   CHECK (type   IN ('BASAL_CHECK','CARB_FACTOR','ISF_ONE_UNIT')),
    CONSTRAINT chk_experiment_status CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','ABANDONED'))
);

CREATE TABLE IF NOT EXISTS experiment_readings (
    id              UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    experiment_id   UUID             NOT NULL,
    recorded_at     TIMESTAMPTZ      NOT NULL,
    glucose_mmol    DOUBLE PRECISION NOT NULL,
    minutes_elapsed INTEGER          NOT NULL DEFAULT 0,
    label           VARCHAR(50),
    CONSTRAINT fk_exp_readings_experiment FOREIGN KEY (experiment_id) REFERENCES experiments(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_experiments_user_id   ON experiments(user_id);
CREATE INDEX IF NOT EXISTS idx_experiments_status    ON experiments(status);
CREATE INDEX IF NOT EXISTS idx_exp_readings_exp_id   ON experiment_readings(experiment_id);
CREATE INDEX IF NOT EXISTS idx_exp_readings_recorded ON experiment_readings(recorded_at);

COMMENT ON TABLE experiments IS
    'Controlled ISF and Carb Ratio determination experiments run by the user.';
COMMENT ON TABLE experiment_readings IS
    'Timestamped glucose readings recorded during an active experiment.';
