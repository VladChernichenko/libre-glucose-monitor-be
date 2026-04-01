ALTER TABLE clinical_knowledge_chunk
    ADD COLUMN IF NOT EXISTS source_name VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_url TEXT,
    ADD COLUMN IF NOT EXISTS source_title VARCHAR(255),
    ADD COLUMN IF NOT EXISTS source_topic VARCHAR(128),
    ADD COLUMN IF NOT EXISTS source_published_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(32) DEFAULT 'INTERNAL';

CREATE INDEX IF NOT EXISTS idx_clinical_knowledge_chunk_source_type
    ON clinical_knowledge_chunk (source_type);

CREATE INDEX IF NOT EXISTS idx_clinical_knowledge_chunk_source_topic
    ON clinical_knowledge_chunk (source_topic);

