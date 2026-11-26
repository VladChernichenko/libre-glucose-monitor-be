-- =============================================================================
-- One-off upgrade of an existing database to this build's schema.
--
-- Why: migrations are edited in place when they own the table (project rule:
-- edit the migration that owns the table), and Flyway never re-runs them. So an
-- existing database lacks what V1/V4/V5/V6 gained and still has what they lost;
-- `flyway repair` alone only rewrites checksums, Hibernate `ddl-auto: validate`
-- then refuses to start, and V16 needs pgvector.
--
-- Applies to a database at:
--   * V12 - created from old main (before feat/assistant-chat). After this
--           script, V13 drops experiments, experiment_readings,
--           verification_events and verification_summary IRREVERSIBLY.
--   * V22 - main before the per-meal-window carb ratio: has the single
--           user_settings.grams_per_unit and the legacy carb_ratio.
--   * V23 - main after PR #40 (feat/carb-ratio-meal-windows, first part): has
--           the four window columns and still grams_per_unit and carb_ratio.
-- user_settings: each unset meal-window ratio takes grams_per_unit (from V12,
-- where there is none, it is derived once as 10 * isf / carb_ratio); then the
-- script DROPS grams_per_unit and carb_ratio.
--
-- Builds before this change read grams_per_unit and carb_ratio. Running this
-- script under a live older build breaks its settings loads, and after
-- `flyway repair` an older jar cannot start (V1 checksum). Never run it while an
-- older build serves traffic.
--
-- PRODUCTION ORDER (do not skip or reorder):
--   1. Pause auto-deploy, or stop the service, so no older build is running.
--   2. BACK UP the database:  pg_dump.
--   3. Run this script:       psql -v ON_ERROR_STOP=1 -d <db> -f scripts/upgrade-existing-db.sql
--      (the server must have the pgvector extension available; the role must be
--      allowed to CREATE EXTENSION).
--   4. flyway repair          (accepts the new checksums of the edited V1, V4, V5, V6).
--   5. Deploy or start this build. Flyway applies any pending migration (V13-V23
--      from V12, V23 from V22, none from V23), then Hibernate validates.
--   6. Resume auto-deploy.
--
-- ROLLBACK: restore the step-2 backup and redeploy the previous build. The
-- dropped columns cannot be recovered any other way.
--
-- Idempotent: safe to run more than once. Covered by ExistingDbUpgradeIT.
-- =============================================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS vector;

-- users: inactivity flag for the CGM sync lanes (V1 on this branch). Existing users start active,
-- with "now" as their last activity, so nobody is dropped from sync by the upgrade itself.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS is_active        BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS last_activity_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- user_settings: correction target and rescue carbs (V1 on this branch).
ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS target_glucose_mmol DOUBLE PRECISION NOT NULL DEFAULT 5.55,
    ADD COLUMN IF NOT EXISTS rescue_carbs_g      INTEGER          NOT NULL DEFAULT 15;

-- user_settings: the insulin-to-carb ratio, grams of carbohydrate per unit, one per meal window
-- (V1 on this branch). The single grams_per_unit they replace is dropped, and so is the legacy
-- carb_ratio (mmol/L per 10 g), which nothing reads any more.
--   * Deployed at V22 (has grams_per_unit): each unset window takes grams_per_unit, then the
--     column goes, and its CHECK with it.
--   * Older, from main's V12 (neither grams_per_unit nor the windows): each window is derived once
--     as 10 * isf / carb_ratio, the ICR that produces the same rise as (grams / 10) * carb_ratio,
--     before carb_ratio is dropped.
-- A ratio outside 3-30 is neither copied nor derived: that window stays NULL (no ratio, dosing
-- refused) and has to be typed in. A second run finds the windows and neither old column, and
-- changes nothing.
DO $$
DECLARE
    has_single     BOOLEAN := EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'user_settings'
          AND column_name = 'grams_per_unit');
    has_windows    BOOLEAN := EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'user_settings'
          AND column_name = 'grams_per_unit_breakfast');
    has_carb_ratio BOOLEAN := EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'user_settings'
          AND column_name = 'carb_ratio');
BEGIN
    ALTER TABLE user_settings
        ADD COLUMN IF NOT EXISTS grams_per_unit_breakfast DOUBLE PRECISION,
        ADD COLUMN IF NOT EXISTS grams_per_unit_lunch     DOUBLE PRECISION,
        ADD COLUMN IF NOT EXISTS grams_per_unit_dinner    DOUBLE PRECISION,
        ADD COLUMN IF NOT EXISTS grams_per_unit_night     DOUBLE PRECISION;

    IF has_single THEN
        UPDATE user_settings
        SET grams_per_unit_breakfast = COALESCE(grams_per_unit_breakfast, grams_per_unit),
            grams_per_unit_lunch     = COALESCE(grams_per_unit_lunch, grams_per_unit),
            grams_per_unit_dinner    = COALESCE(grams_per_unit_dinner, grams_per_unit),
            grams_per_unit_night     = COALESCE(grams_per_unit_night, grams_per_unit)
        WHERE grams_per_unit BETWEEN 3 AND 30;
        ALTER TABLE user_settings DROP COLUMN IF EXISTS grams_per_unit;
    ELSIF NOT has_windows AND has_carb_ratio THEN
        UPDATE user_settings
        SET grams_per_unit_breakfast = COALESCE(grams_per_unit_breakfast, 10.0 * isf / carb_ratio),
            grams_per_unit_lunch     = COALESCE(grams_per_unit_lunch, 10.0 * isf / carb_ratio),
            grams_per_unit_dinner    = COALESCE(grams_per_unit_dinner, 10.0 * isf / carb_ratio),
            grams_per_unit_night     = COALESCE(grams_per_unit_night, 10.0 * isf / carb_ratio)
        WHERE isf > 0 AND carb_ratio > 0
          AND (10.0 * isf / carb_ratio) BETWEEN 3 AND 30;
    END IF;

    -- Its CHECK (chk_user_settings_carb_ratio_positive) goes with it.
    ALTER TABLE user_settings DROP COLUMN IF EXISTS carb_ratio;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_user_settings_gpu_breakfast'
          AND conrelid = 'user_settings'::regclass
    ) THEN
        ALTER TABLE user_settings
            ADD CONSTRAINT chk_user_settings_gpu_breakfast
            CHECK (grams_per_unit_breakfast IS NULL OR (grams_per_unit_breakfast >= 3 AND grams_per_unit_breakfast <= 30));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_user_settings_gpu_lunch'
          AND conrelid = 'user_settings'::regclass
    ) THEN
        ALTER TABLE user_settings
            ADD CONSTRAINT chk_user_settings_gpu_lunch
            CHECK (grams_per_unit_lunch IS NULL OR (grams_per_unit_lunch >= 3 AND grams_per_unit_lunch <= 30));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_user_settings_gpu_dinner'
          AND conrelid = 'user_settings'::regclass
    ) THEN
        ALTER TABLE user_settings
            ADD CONSTRAINT chk_user_settings_gpu_dinner
            CHECK (grams_per_unit_dinner IS NULL OR (grams_per_unit_dinner >= 3 AND grams_per_unit_dinner <= 30));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_user_settings_gpu_night'
          AND conrelid = 'user_settings'::regclass
    ) THEN
        ALTER TABLE user_settings
            ADD CONSTRAINT chk_user_settings_gpu_night
            CHECK (grams_per_unit_night IS NULL OR (grams_per_unit_night >= 3 AND grams_per_unit_night <= 30));
    END IF;
END $$;

-- notes: assistant chat carb band, context tags and originating chat message (V1 on this branch)
ALTER TABLE notes
    ADD COLUMN IF NOT EXISTS carbs_low       DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS carbs_high      DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS context_tags    JSONB,
    ADD COLUMN IF NOT EXISTS chat_message_id UUID;

-- notes: which insulin each dose was (V1 on this branch)
ALTER TABLE notes
    ADD COLUMN IF NOT EXISTS insulin_code VARCHAR(32);

-- user_digital_twin: learned activity gains (V8 on this branch)
ALTER TABLE user_digital_twin
    ADD COLUMN IF NOT EXISTS activity_gain_scale         DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    ADD COLUMN IF NOT EXISTS activity_tail_half_life_min DOUBLE PRECISION NOT NULL DEFAULT 120,
    ADD COLUMN IF NOT EXISTS activity_applied            BOOLEAN          NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS activity_samples            INTEGER          NOT NULL DEFAULT 0;

-- clinical_knowledge_chunk: clinical library columns, upsert key and vector index (V1 on this branch)
ALTER TABLE clinical_knowledge_chunk
    ADD COLUMN IF NOT EXISTS embedding     vector(1536),
    ADD COLUMN IF NOT EXISTS citation      TEXT,
    ADD COLUMN IF NOT EXISTS chunk_index   INT,
    ADD COLUMN IF NOT EXISTS source_doc_id VARCHAR(128);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'uq_clinical_knowledge_chunk_doc_chunk'
          AND conrelid = 'clinical_knowledge_chunk'::regclass
    ) THEN
        ALTER TABLE clinical_knowledge_chunk
            ADD CONSTRAINT uq_clinical_knowledge_chunk_doc_chunk UNIQUE (source_doc_id, chunk_index);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_clinical_knowledge_chunk_embedding
    ON clinical_knowledge_chunk USING hnsw (embedding vector_cosine_ops);

-- V4 on main seeded 8 internal chunks besides the disclaimer; V4 on this branch seeds only the
-- disclaimer and the library comes from the ingestion script (rows with a source_doc_id).
DELETE FROM clinical_knowledge_chunk
WHERE source_doc_id IS NULL AND title <> 'General safety disclaimer';

COMMIT;
