-- Internal clinical knowledge chunks.
INSERT INTO clinical_knowledge_chunk
    (id, title, content, condition_tag, insulin_type_tag, risk_class, evidence_level, active, updated_at)
SELECT gen_random_uuid(), v.title, v.content, v.condition_tag, v.insulin_type_tag, v.risk_class, v.evidence_level, v.active, NOW()
FROM (VALUES
    ('Hypoglycemia immediate response',
     'If glucose is low, follow your hypo protocol and re-check after treatment. Avoid over-correcting with large carb loads.',
     'HYPO',      NULL, 'HIGH',   'GUIDELINE',     TRUE),
    ('Post-meal rise review',
     'For repeated post-meal spikes, review carb estimate and bolus timing. Late bolus can shift peak upward.',
     'POST_MEAL', NULL, 'MEDIUM', 'BEST_PRACTICE', TRUE),
    ('Hyperglycemia correction spacing',
     'When correcting high glucose, avoid stacking multiple corrections too close together.',
     'HYPER',     NULL, 'MEDIUM', 'BEST_PRACTICE', TRUE),
    ('Rising trend caution',
     'Fast-rising trends may indicate undercounted carbs or delayed insulin action.',
     'RISING',    NULL, 'MEDIUM', 'HEURISTIC',     TRUE),
    ('General safety disclaimer',
     'This analysis is educational and should not replace clinician advice or your established treatment plan.',
     'GENERAL',   NULL, 'LOW',    'SAFETY',        TRUE)
) AS v(title, content, condition_tag, insulin_type_tag, risk_class, evidence_level, active)
WHERE NOT EXISTS (
    SELECT 1 FROM clinical_knowledge_chunk c WHERE c.title = v.title
);

-- Rule15 external metadata references.
INSERT INTO clinical_knowledge_chunk (
    id, title, content, condition_tag, insulin_type_tag, risk_class, evidence_level, active, updated_at,
    source_name, source_url, source_title, source_topic, source_type
)
SELECT gen_random_uuid(), v.title, v.content, v.condition_tag, v.insulin_type_tag, v.risk_class, v.evidence_level, v.active, NOW(),
       v.source_name, v.source_url, v.source_title, v.source_topic, v.source_type
FROM (VALUES
    ('Rule15: hypo protocol reference',                'Metadata-only reference to Rule15 educational material.', 'HYPO',      NULL, 'HIGH',   'EDUCATIONAL', TRUE, 'rule15s', 'https://rule15s.com/', 'Правило 15: гипогликемия и самоконтроль', 'hypoglycemia',   'EXTERNAL_METADATA'),
    ('Rule15: hyperglycemia and ketones reference',    'Metadata-only reference to Rule15 educational material.', 'HYPER',     NULL, 'HIGH',   'EDUCATIONAL', TRUE, 'rule15s', 'https://rule15s.com/', 'Правило 15: важное о кетонах',           'hyperglycemia',  'EXTERNAL_METADATA'),
    ('Rule15: post-meal review reference',             'Metadata-only reference to Rule15 educational material.', 'POST_MEAL', NULL, 'MEDIUM', 'EDUCATIONAL', TRUE, 'rule15s', 'https://rule15s.com/', 'Правило 15: питание при диабете 1 типа', 'post_meal',      'EXTERNAL_METADATA'),
    ('Rule15: trend and self-management reference',    'Metadata-only reference to Rule15 educational material.', 'RISING',    NULL, 'MEDIUM', 'EDUCATIONAL', TRUE, 'rule15s', 'https://rule15s.com/', 'Правило 15: база знаний по самоконтролю','glucose_trends', 'EXTERNAL_METADATA')
) AS v(title, content, condition_tag, insulin_type_tag, risk_class, evidence_level, active,
       source_name, source_url, source_title, source_topic, source_type)
WHERE NOT EXISTS (
    SELECT 1 FROM clinical_knowledge_chunk c
    WHERE c.source_name = v.source_name AND c.source_topic = v.source_topic AND c.title = v.title
);
