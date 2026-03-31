INSERT INTO clinical_knowledge_chunk (
    id, title, content, condition_tag, insulin_type_tag, risk_class, evidence_level, active, updated_at,
    source_name, source_url, source_title, source_topic, source_type
)
VALUES
    (gen_random_uuid(), 'Rule15: hypo protocol reference', 'Metadata-only reference to Rule15 educational material.', 'HYPO', NULL, 'HIGH', 'EDUCATIONAL', TRUE, NOW(),
     'rule15s', 'https://rule15s.com/', 'Правило 15: гипогликемия и самоконтроль', 'hypoglycemia', 'EXTERNAL_METADATA'),
    (gen_random_uuid(), 'Rule15: hyperglycemia and ketones reference', 'Metadata-only reference to Rule15 educational material.', 'HYPER', NULL, 'HIGH', 'EDUCATIONAL', TRUE, NOW(),
     'rule15s', 'https://rule15s.com/', 'Правило 15: важное о кетонах', 'hyperglycemia', 'EXTERNAL_METADATA'),
    (gen_random_uuid(), 'Rule15: post-meal review reference', 'Metadata-only reference to Rule15 educational material.', 'POST_MEAL', NULL, 'MEDIUM', 'EDUCATIONAL', TRUE, NOW(),
     'rule15s', 'https://rule15s.com/', 'Правило 15: питание при диабете 1 типа', 'post_meal', 'EXTERNAL_METADATA'),
    (gen_random_uuid(), 'Rule15: trend and self-management reference', 'Metadata-only reference to Rule15 educational material.', 'RISING', NULL, 'MEDIUM', 'EDUCATIONAL', TRUE, NOW(),
     'rule15s', 'https://rule15s.com/', 'Правило 15: база знаний по самоконтролю', 'glucose_trends', 'EXTERNAL_METADATA');

