-- Reference insulin PK parameters (literature / product summaries / OpenAPS defaults).
INSERT INTO insulin_catalog (code, category, display_name, peak_minutes, dia_hours, half_life_minutes, onset_minutes, description) VALUES
('FIASP',   'RAPID',       'Fiasp (faster aspart)',            55,  4.5,  52,   7,
 'Ultra-rapid bolus: OpenAPS-style exponential IOB (peak ~55 min, DIA ~4.5 h). Half-life ~50–60 min.'),
('APIDRA',  'RAPID',       'Apidra (insulin glulisine)',       75,  4.0,  45,  20,
 'Rapid bolus: OpenAPS rapid-acting peak 75 min, DIA ~4 h. Half-life ~40–50 min.'),
('TRESIBA', 'LONG_ACTING', 'Tresiba (insulin degludec)',     NULL, 42.0, 1500, 60,
 'Basal: terminal half-life ~25 h; clinical duration up to ~42 h. Bolus IOB uses rapid insulin only.'),
('LANTUS',  'LONG_ACTING', 'Lantus (insulin glargine 100 U/mL)', NULL, 24.0,  720, 90,
 'Basal: ~24 h effect; half-life ~12 h. Bolus IOB uses rapid insulin only.')
ON CONFLICT (code) DO UPDATE SET
    category          = EXCLUDED.category,
    display_name      = EXCLUDED.display_name,
    peak_minutes      = EXCLUDED.peak_minutes,
    dia_hours         = EXCLUDED.dia_hours,
    half_life_minutes = EXCLUDED.half_life_minutes,
    onset_minutes     = EXCLUDED.onset_minutes,
    description       = EXCLUDED.description;
