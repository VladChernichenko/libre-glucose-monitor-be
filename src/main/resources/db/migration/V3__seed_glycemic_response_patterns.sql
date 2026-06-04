-- Glycemic response patterns. Maps meal macros (GI, GL, fat, protein, fiber) to expected
-- CGM curve and Warsaw-Method bolus strategy. Bolus tiers:
--   1 FPU (~100 kcal fat+protein) -> Normal, 3 h
--   2 FPU                         -> Normal, 4 h
--   3 FPU                         -> Extended, 5 h
--   4 FPU                         -> Dual Wave, 8 h
-- Meal sequencing: 1 = eat first (fiber), 3 = eat last (simple carbs).

-- ON CONFLICT keeps this migration safe to re-run via `flyway repair` or a
-- one-off baseline reset; production never re-runs versioned migrations.
INSERT INTO glycemic_response_patterns
    (pattern_name, gi_min, gi_max, gl_min, gl_max, min_fat_grams, min_protein_grams,
     has_fiber_barrier, curve_description, bolus_strategy, suggested_duration_hours, meal_sequencing_priority)
VALUES
-- Fast Spike (high-GI / high-GL pure carbs)
('Fast Spike',  70, NULL, 20.0, NULL, NULL, NULL, FALSE,
 'Sharp rise within 30–60 min post-meal; steep peak followed by rapid fall. '
 'Typical for white bread, glucose drinks, sugary cereals.',
 'Normal',    2.5, 3),

-- Slow Climb (low-GI ≤55)
('Slow Climb', NULL, 55,  NULL, NULL, NULL, NULL, FALSE,
 'Gradual rise peaking at 1.5–2h; lower amplitude than fast-GI foods. '
 'Typical for legumes, pasta al dente, most whole grains.',
 'Normal',    3.5, 2),

-- Double Wave (≥4 FPU)
('Double Wave', NULL, NULL, NULL, NULL, 40.0, 25.0, FALSE,
 'Biphasic curve: first peak from carbohydrates at 1–2h; second rise at 3–5h driven by '
 'fat-delayed gastric emptying and protein gluconeogenesis. Classic after pizza, burgers, '
 'creamy pasta. Warsaw Method: ≥4 FPU -> Dual Wave 30% now / 70% over 8h.',
 'Dual Wave', 8.0, 2),

-- Flat Plateau (~3 FPU, low GL)
('Flat Plateau', NULL, NULL, NULL, 10.0, NULL, 30.0, FALSE,
 'No acute carb spike; sustained plateau 5–8h from protein gluconeogenesis. '
 'Typical for high-protein low-carb meals: steak, eggs, cheese, nuts. '
 'Warsaw Method: ~3 FPU -> Extended bolus over 5h.',
 'Extended',  5.0, 2),

-- Blunted Curve (fiber barrier)
('Blunted Curve', NULL, NULL, NULL, NULL, NULL, NULL, TRUE,
 'Reduced peak amplitude (-25–40%) and delayed time-to-peak (+30–45 min) caused by soluble '
 'fiber increasing gastric viscosity. Eat fiber-rich foods FIRST to maximise blunting. '
 'Adjust bolus timing: inject 0–5 min before eating instead of 15–20 min pre-bolus.',
 'Normal',    4.0, 1),

-- Light FPU (~1 FPU)
('Light FPU', NULL, NULL, NULL, NULL,  8.0, 10.0, FALSE,
 'Modest protein-fat load produces a gentle secondary glucose rise 1.5–3h post-meal '
 'via protein gluconeogenesis and fat-delayed gastric emptying. '
 'Warsaw Method: ~1 FPU -> Normal bolus with 3h absorption window. '
 'Typical for: eggs, yoghurt with nuts, light pasta with chicken.',
 'Normal',    3.0, 2),

-- Moderate FPU (~2 FPU)
('Moderate FPU', NULL, NULL, NULL, NULL, 15.0, 18.0, FALSE,
 'Moderate protein-fat load causes a noticeable secondary glucose rise at 2–4h '
 'driven by fat-slowed gastric emptying and protein gluconeogenesis. '
 'Warsaw Method: ~2 FPU -> Normal bolus with 4h absorption window. '
 'Typical for: meat with cheese sauce, salmon with rice, omelette with avocado.',
 'Normal',    4.0, 2)
ON CONFLICT (pattern_name) DO NOTHING;
