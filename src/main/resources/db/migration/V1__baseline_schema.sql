-- =============================================================================
-- Glucose Monitor — Baseline Schema (V1)
-- =============================================================================
-- Consolidated from the legacy V1–V25 Flyway chain.
--
-- Dropped as dead (no repository, no INSERT/UPDATE anywhere in the codebase):
--   nightscout_config, carbs_entries, glucose_readings, insulin_doses,
--   user_configurations.
--
-- Standardizations:
--   * All timestamps are TIMESTAMPTZ (UTC).
--   * All user-scoped tables FK users(id) ON DELETE CASCADE.
--   * cob_settings is the single source of truth for COB/ISF/half-life.
--   * All CREATE TABLE / CREATE INDEX use IF NOT EXISTS for idempotency.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- -----------------------------------------------------------------------------
-- Identity
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email                    VARCHAR(255) UNIQUE NOT NULL,
    username                 VARCHAR(255) UNIQUE NOT NULL,
    password                 VARCHAR(255) NOT NULL,
    full_name                VARCHAR(255),
    role                     VARCHAR(50)  NOT NULL DEFAULT 'USER',
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    account_non_expired      BOOLEAN      NOT NULL DEFAULT TRUE,
    credentials_non_expired  BOOLEAN      NOT NULL DEFAULT TRUE,
    account_non_locked       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------------------------------
-- Per-user COB / ISF parameters (single source of truth)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cob_settings (
    id                  UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID             NOT NULL UNIQUE,
    carb_ratio          DOUBLE PRECISION NOT NULL DEFAULT 2.0,
    isf                 DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    carb_half_life      INTEGER          NOT NULL DEFAULT 45,
    max_cob_duration    INTEGER          NOT NULL DEFAULT 240,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_cob_settings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_cob_settings_carb_ratio_positive CHECK (carb_ratio > 0)
);
CREATE INDEX IF NOT EXISTS idx_cob_settings_user_id    ON cob_settings(user_id);
CREATE INDEX IF NOT EXISTS idx_cob_settings_created_at ON cob_settings(created_at);

COMMENT ON TABLE cob_settings IS
    'Per-user COB/ISF/half-life parameters. Replaces legacy user_configurations.';

-- -----------------------------------------------------------------------------
-- Notes (carbs / insulin / meals + nutrition profile)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS notes (
    id                  UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID             NOT NULL,
    timestamp           TIMESTAMPTZ      NOT NULL,
    carbs               DOUBLE PRECISION NOT NULL,
    insulin             DOUBLE PRECISION NOT NULL,
    meal                VARCHAR(50)      NOT NULL,
    comment             TEXT,
    glucose_value       DOUBLE PRECISION,
    detailed_input      TEXT,
    insulin_dose        TEXT,
    mock_data           BOOLEAN          NOT NULL DEFAULT FALSE,
    nutrition_profile   JSON,
    absorption_mode     VARCHAR(32),
    type                VARCHAR(20)      NOT NULL DEFAULT 'normal',
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_notes_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_notes_user_timestamp ON notes(user_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_notes_timestamp      ON notes(timestamp);
CREATE INDEX IF NOT EXISTS idx_notes_created_at     ON notes(created_at);
CREATE INDEX IF NOT EXISTS idx_notes_user_type      ON notes(user_id, type);

-- -----------------------------------------------------------------------------
-- Shared CGM reading cache (unlimited history).
-- Holds CGM points from BOTH data sources: Nightscout and LibreLinkUp.
-- The data_source column identifies the origin; external_id is the upstream record
-- id when the source provides one (Nightscout `_id`, Libre measurement id).
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS cgm_readings (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL,
    data_source     VARCHAR(20)  NOT NULL,
    external_id     VARCHAR(255),
    sgv             INTEGER,
    date_timestamp  BIGINT,
    date_string     VARCHAR(255),
    trend           INTEGER,
    direction       VARCHAR(50),
    device          VARCHAR(255),
    type            VARCHAR(50),
    utc_offset      INTEGER,
    sys_time        VARCHAR(255),
    last_updated    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_cgm_readings_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_cgm_readings_data_source CHECK (data_source IN ('NIGHTSCOUT', 'LIBRE_LINK_UP'))
);
CREATE INDEX IF NOT EXISTS idx_cgm_readings_user_id      ON cgm_readings(user_id);
CREATE INDEX IF NOT EXISTS idx_cgm_readings_last_updated ON cgm_readings(last_updated);
CREATE INDEX IF NOT EXISTS idx_cgm_readings_user_date    ON cgm_readings(user_id, date_timestamp);
CREATE INDEX IF NOT EXISTS idx_cgm_readings_user_source  ON cgm_readings(user_id, data_source);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cgm_readings_user_source_external
    ON cgm_readings(user_id, data_source, external_id)
    WHERE external_id IS NOT NULL AND btrim(external_id) <> '';
CREATE UNIQUE INDEX IF NOT EXISTS uk_cgm_readings_user_source_ts
    ON cgm_readings(user_id, data_source, date_timestamp)
    WHERE external_id IS NULL OR btrim(external_id) = '';

COMMENT ON TABLE  cgm_readings              IS 'Shared CGM reading cache for all supported data sources (Nightscout, LibreLinkUp).';
COMMENT ON COLUMN cgm_readings.data_source  IS 'Origin of the reading: NIGHTSCOUT or LIBRE_LINK_UP. Matches user_data_source_config.data_source.';
COMMENT ON COLUMN cgm_readings.external_id  IS 'Upstream record id from the source (Nightscout _id, Libre measurement id). Null if the source did not supply one.';

-- -----------------------------------------------------------------------------
-- Data source config (Nightscout + LibreLinkUp) — replaces legacy nightscout_config
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_data_source_config (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    data_source             VARCHAR(50)  NOT NULL CHECK (data_source IN ('NIGHTSCOUT', 'LIBRE_LINK_UP')),
    nightscout_url          VARCHAR(500),
    nightscout_api_secret   VARCHAR(255),
    nightscout_api_token    VARCHAR(255),
    libre_email             VARCHAR(255),
    libre_password          VARCHAR(255),
    libre_patient_id        VARCHAR(255),
    libre_locale            VARCHAR(20),
    is_active               BOOLEAN      NOT NULL DEFAULT TRUE,
    last_used               TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_user_data_source_config_user_id     ON user_data_source_config(user_id);
CREATE INDEX IF NOT EXISTS idx_user_data_source_config_user_source ON user_data_source_config(user_id, data_source);
CREATE INDEX IF NOT EXISTS idx_user_data_source_config_active      ON user_data_source_config(user_id, data_source, is_active);
CREATE INDEX IF NOT EXISTS idx_user_data_source_config_last_used   ON user_data_source_config(last_used);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_data_source_config_unique_active
    ON user_data_source_config(user_id, data_source)
    WHERE is_active = TRUE;

COMMENT ON TABLE  user_data_source_config             IS 'Per-user data source configurations for Nightscout and LibreLinkUp.';
COMMENT ON COLUMN user_data_source_config.libre_locale IS 'Accept-Language / region tag for LibreLinkUp regional endpoint (e.g. "fr-FR" -> api-fr.libreview.io).';

-- -----------------------------------------------------------------------------
-- Insulin catalog + user insulin preferences
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS insulin_catalog (
    id                  UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    code                VARCHAR(32)      NOT NULL UNIQUE,
    category            VARCHAR(20)      NOT NULL CHECK (category IN ('RAPID', 'LONG_ACTING')),
    display_name        VARCHAR(128)     NOT NULL,
    peak_minutes        INTEGER,
    dia_hours           DOUBLE PRECISION NOT NULL,
    half_life_minutes   DOUBLE PRECISION NOT NULL,
    onset_minutes       INTEGER,
    description         TEXT
);

CREATE TABLE IF NOT EXISTS user_insulin_preferences (
    id                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID         NOT NULL UNIQUE,
    rapid_insulin_id            UUID         NOT NULL REFERENCES insulin_catalog(id),
    long_acting_insulin_id      UUID         NOT NULL REFERENCES insulin_catalog(id),
    long_acting_injection_time  TIME,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_insulin_preferences_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_user_insulin_preferences_rapid ON user_insulin_preferences(rapid_insulin_id);

-- -----------------------------------------------------------------------------
-- Glucose sync polling state
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_glucose_sync_state (
    id                             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                        UUID         NOT NULL UNIQUE,
    last_checked_at                TIMESTAMPTZ,
    last_new_data_at               TIMESTAMPTZ,
    last_seen_entry_timestamp      BIGINT,
    next_poll_at                   TIMESTAMPTZ,
    consecutive_no_change_count    INTEGER      NOT NULL DEFAULT 0,
    last_status                    VARCHAR(32),
    updated_at                     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_user_glucose_sync_state_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_user_glucose_sync_state_next_poll ON user_glucose_sync_state(next_poll_at);

-- -----------------------------------------------------------------------------
-- Clinical knowledge base
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS clinical_knowledge_chunk (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    title               VARCHAR(200) NOT NULL,
    content             TEXT         NOT NULL,
    condition_tag       VARCHAR(64)  NOT NULL,
    insulin_type_tag    VARCHAR(64),
    risk_class          VARCHAR(32),
    evidence_level      VARCHAR(32),
    active              BOOLEAN      NOT NULL DEFAULT TRUE,
    source_name         VARCHAR(64),
    source_url          TEXT,
    source_title        VARCHAR(255),
    source_topic        VARCHAR(128),
    source_published_at TIMESTAMPTZ,
    source_type         VARCHAR(32)  DEFAULT 'INTERNAL',
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_clinical_knowledge_chunk_condition    ON clinical_knowledge_chunk(condition_tag);
CREATE INDEX IF NOT EXISTS idx_clinical_knowledge_chunk_source_type  ON clinical_knowledge_chunk(source_type);
CREATE INDEX IF NOT EXISTS idx_clinical_knowledge_chunk_source_topic ON clinical_knowledge_chunk(source_topic);

-- -----------------------------------------------------------------------------
-- AI analysis trace
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_analysis_trace (
    id                  UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID             NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    model_id            VARCHAR(128)     NOT NULL,
    window_hours        INTEGER          NOT NULL,
    context_hash        VARCHAR(128)     NOT NULL,
    evidence_chunk_ids  TEXT,
    confidence          DOUBLE PRECISION NOT NULL,
    latency_ms          BIGINT           NOT NULL,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ai_analysis_trace_user_created ON ai_analysis_trace(user_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- Glycemic response patterns (reference data — seeded in V3)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS glycemic_response_patterns (
    id                       SERIAL       PRIMARY KEY,
    pattern_name             VARCHAR(64)  NOT NULL UNIQUE,
    gi_min                   INTEGER,
    gi_max                   INTEGER,
    gl_min                   NUMERIC(5,1),
    gl_max                   NUMERIC(5,1),
    min_fat_grams            NUMERIC(5,1),
    min_protein_grams        NUMERIC(5,1),
    has_fiber_barrier        BOOLEAN      NOT NULL DEFAULT FALSE,
    curve_description        TEXT         NOT NULL,
    bolus_strategy           VARCHAR(32)  NOT NULL CHECK (bolus_strategy IN ('Normal', 'Extended', 'Dual Wave')),
    suggested_duration_hours NUMERIC(4,1) NOT NULL,
    meal_sequencing_priority SMALLINT     NOT NULL CHECK (meal_sequencing_priority BETWEEN 1 AND 3),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_grp_gi_gl ON glycemic_response_patterns(gi_min, gi_max, gl_min, gl_max);
CREATE INDEX IF NOT EXISTS idx_grp_fiber ON glycemic_response_patterns(has_fiber_barrier);

-- -----------------------------------------------------------------------------
-- JWT revocation (durable token blacklist)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS token_blacklist (
    token_hash  VARCHAR(64)  PRIMARY KEY,
    expires_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_token_blacklist_expires_at ON token_blacklist(expires_at);



