CREATE TABLE clinical_knowledge_chunk (
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

CREATE INDEX idx_clinical_knowledge_chunk_condition
    ON clinical_knowledge_chunk (condition_tag);

INSERT INTO clinical_knowledge_chunk (id, title, content, condition_tag, insulin_type_tag, risk_class, evidence_level, active, updated_at)
VALUES
(gen_random_uuid(), 'Hypoglycemia immediate response', 'If glucose is low, follow your hypo protocol and re-check after treatment. Avoid over-correcting with large carb loads.', 'HYPO', NULL, 'HIGH', 'GUIDELINE', TRUE, CURRENT_TIMESTAMP),
(gen_random_uuid(), 'Post-meal rise review', 'For repeated post-meal spikes, review carb estimate and bolus timing. Late bolus can shift peak upward.', 'POST_MEAL', NULL, 'MEDIUM', 'BEST_PRACTICE', TRUE, CURRENT_TIMESTAMP),
(gen_random_uuid(), 'Hyperglycemia correction spacing', 'When correcting high glucose, avoid stacking multiple corrections too close together.', 'HYPER', NULL, 'MEDIUM', 'BEST_PRACTICE', TRUE, CURRENT_TIMESTAMP),
(gen_random_uuid(), 'Rising trend caution', 'Fast-rising trends may indicate undercounted carbs or delayed insulin action.', 'RISING', NULL, 'MEDIUM', 'HEURISTIC', TRUE, CURRENT_TIMESTAMP),
(gen_random_uuid(), 'General safety disclaimer', 'This analysis is educational and should not replace clinician advice or your established treatment plan.', 'GENERAL', NULL, 'LOW', 'SAFETY', TRUE, CURRENT_TIMESTAMP);
