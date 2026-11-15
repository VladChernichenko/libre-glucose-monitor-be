-- More reference insulins (product summaries / OpenAPS curve defaults), same conventions as V2:
-- rapid rows drive the exponential bolus IOB curve (peak_minutes, dia_hours); long-acting rows
-- drive the basal EGP-suppression plateau (dia_hours). NPH is left out: its pronounced peak does
-- not fit the flat basal plateau.
INSERT INTO insulin_catalog (code, category, display_name, peak_minutes, dia_hours, half_life_minutes, onset_minutes, description) VALUES
('NOVORAPID', 'RAPID',       'NovoRapid / NovoLog (insulin aspart)',  75,  4.0,  81,  15,
 'Rapid bolus: OpenAPS rapid-acting peak 75 min, DIA ~4 h. Half-life ~80 min.'),
('HUMALOG',   'RAPID',       'Humalog (insulin lispro)',              75,  4.0,  60,  15,
 'Rapid bolus: OpenAPS rapid-acting peak 75 min, DIA ~4 h. Half-life ~60 min.'),
('LYUMJEV',   'RAPID',       'Lyumjev (ultra-rapid lispro)',          55,  4.5,  44,   5,
 'Ultra-rapid bolus: OpenAPS ultra-rapid peak 55 min, DIA ~4.5 h. Half-life ~44 min.'),
('REGULAR',   'RAPID',       'Regular human insulin (Actrapid / Humulin R)', 150, 7.0, 90, 30,
 'Short-acting bolus: peak ~2-3 h, duration ~6-8 h. Slower than the analogues; the exponential IOB curve is an approximation.'),
('LEVEMIR',   'LONG_ACTING', 'Levemir (insulin detemir)',           NULL, 20.0, 360,  90,
 'Basal: dose-dependent duration, ~20 h at typical doses (often injected twice daily); half-life ~5-7 h. Bolus IOB uses rapid insulin only.'),
('TOUJEO',    'LONG_ACTING', 'Toujeo (insulin glargine 300 U/mL)',  NULL, 36.0, 1140, 360,
 'Basal: flatter than glargine U100, effect up to ~36 h; half-life ~19 h. Bolus IOB uses rapid insulin only.')
ON CONFLICT (code) DO UPDATE SET
    category          = EXCLUDED.category,
    display_name      = EXCLUDED.display_name,
    peak_minutes      = EXCLUDED.peak_minutes,
    dia_hours         = EXCLUDED.dia_hours,
    half_life_minutes = EXCLUDED.half_life_minutes,
    onset_minutes     = EXCLUDED.onset_minutes,
    description       = EXCLUDED.description;
