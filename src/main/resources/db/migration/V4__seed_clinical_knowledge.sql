-- The GENERAL safety disclaimer, always returned with retrieved passages (spec 6.9). The clinical library
-- itself (ADA/ISPAD sections, PubMed abstracts with embeddings) is loaded by a separate ingestion script.
INSERT INTO clinical_knowledge_chunk
    (id, title, content, condition_tag, insulin_type_tag, risk_class, evidence_level, active, updated_at)
SELECT gen_random_uuid(), v.title, v.content, v.condition_tag, v.insulin_type_tag, v.risk_class, v.evidence_level, v.active, NOW()
FROM (VALUES
    ('General safety disclaimer',
     'This analysis is educational and should not replace clinician advice or your established treatment plan.',
     'GENERAL',   NULL, 'LOW',    'SAFETY',        TRUE)
) AS v(title, content, condition_tag, insulin_type_tag, risk_class, evidence_level, active)
WHERE NOT EXISTS (
    SELECT 1 FROM clinical_knowledge_chunk c WHERE c.title = v.title
);
