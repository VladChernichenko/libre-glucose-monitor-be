-- =============================================================================
-- One-off upgrade of a database created from main (Flyway at V12) to the
-- feat/assistant-chat schema.
--
-- Why: this branch edited V1/V4/V5/V6 in place (project rule: edit the migration
-- that owns the table). Flyway never re-runs V1, so on an existing database the
-- columns added to V1 are missing, `flyway repair` alone only rewrites checksums,
-- Hibernate `ddl-auto: validate` then refuses to start, and V16 needs pgvector.
--
-- ORDER (do not skip or reorder):
--   1. BACK UP the database (pg_dump). V13 drops experiments, experiment_readings,
--      verification_events and verification_summary IRREVERSIBLY.
--   2. Run this script:   psql -v ON_ERROR_STOP=1 -d <db> -f scripts/upgrade-existing-db.sql
--      (the server must have the pgvector extension available; the role must be
--      allowed to CREATE EXTENSION).
--   3. flyway repair      (accepts the new checksums of the edited V1, V4, V5, V6).
--   4. Start the app      (Flyway applies V13-V16, then Hibernate validates).
--
-- Idempotent: safe to run more than once. Covered by ExistingDbUpgradeIT.
-- =============================================================================

BEGIN;

CREATE EXTENSION IF NOT EXISTS vector;

-- user_settings: correction target and rescue carbs (V1 on this branch)
ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS target_glucose_mmol DOUBLE PRECISION NOT NULL DEFAULT 5.55,
    ADD COLUMN IF NOT EXISTS rescue_carbs_g      INTEGER          NOT NULL DEFAULT 15;

-- notes: assistant chat carb band, context tags and originating chat message (V1 on this branch)
ALTER TABLE notes
    ADD COLUMN IF NOT EXISTS carbs_low       DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS carbs_high      DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS context_tags    JSONB,
    ADD COLUMN IF NOT EXISTS chat_message_id UUID;

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
