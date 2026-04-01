CREATE TABLE IF NOT EXISTS clinical_knowledge_chunk (
    id               UUID PRIMARY KEY,
    title            VARCHAR(200) NOT NULL,
    content          TEXT NOT NULL,
    condition_tag    VARCHAR(64) NOT NULL,
    insulin_type_tag VARCHAR(64),
    risk_class       VARCHAR(32),
    evidence_level   VARCHAR(32),
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_clinical_knowledge_chunk_condition
    ON clinical_knowledge_chunk (condition_tag);

INSERT INTO clinical_knowledge_chunk (id, title, content, condition_tag, insulin_type_tag, risk_class, evidence_level, active, updated_at)
SELECT gen_random_uuid(), v.title, v.content, v.condition_tag, v.insulin_type_tag, v.risk_class, v.evidence_level, v.active, CURRENT_TIMESTAMP
FROM (
    VALUES
        ('Hypoglycemia immediate response', 'If glucose is low, follow your hypo protocol and re-check after treatment. Avoid over-correcting with large carb loads.', 'HYPO', NULL, 'HIGH', 'GUIDELINE', TRUE),
        ('Post-meal rise review', 'For repeated post-meal spikes, review carb estimate and bolus timing. Late bolus can shift peak upward.', 'POST_MEAL', NULL, 'MEDIUM', 'BEST_PRACTICE', TRUE),
        ('Hyperglycemia correction spacing', 'When correcting high glucose, avoid stacking multiple corrections too close together.', 'HYPER', NULL, 'MEDIUM', 'BEST_PRACTICE', TRUE),
        ('Rising trend caution', 'Fast-rising trends may indicate undercounted carbs or delayed insulin action.', 'RISING', NULL, 'MEDIUM', 'HEURISTIC', TRUE),
        ('General safety disclaimer', 'This analysis is educational and should not replace clinician advice or your established treatment plan.', 'GENERAL', NULL, 'LOW', 'SAFETY', TRUE)
) AS v(title, content, condition_tag, insulin_type_tag, risk_class, evidence_level, active)
WHERE NOT EXISTS (
    SELECT 1
    FROM clinical_knowledge_chunk c
    WHERE c.title = v.title
);
