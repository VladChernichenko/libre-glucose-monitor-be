-- Missing Warsaw Method FPU tiers 1 and 2.
--
-- V20 covered ≥3 FPU (Flat Plateau, 5h) and ≥4 FPU (Double Wave, 8h).
-- Clinical reference: Pańkowska et al., Pediatric Diabetes 2009/2012.
--   1 FPU = 100 kcal from fat+protein → extend over 3 h
--   2 FPU = 200 kcal from fat+protein → extend over 4 h
--
-- FPU = (fat_g × 9 + protein_g × 4) / 100
-- Thresholds below approximate each tier for typical mixed-meal macros.

-- Pattern 6: Light FPU (1 FPU, 3 h)
-- Triggered by meals with modest fat AND protein (e.g. eggs, yoghurt with nuts, light pasta).
-- Warsaw: 1 FPU → extend bolus over 3 h to cover slow protein gluconeogenesis.
INSERT INTO glycemic_response_patterns
    (pattern_name, gi_min, gi_max, gl_min, gl_max, min_fat_grams, min_protein_grams,
     has_fiber_barrier, curve_description, bolus_strategy, suggested_duration_hours, meal_sequencing_priority)
VALUES
    ('Light FPU',
     NULL, NULL,
     NULL, NULL,
     8.0,   -- fat ≥ 8g
     10.0,  -- protein ≥ 10g  (combined ≈ 1 FPU: 8×9+10×4=112 kcal)
     FALSE,
     'Modest protein-fat load produces a gentle secondary glucose rise 1.5–3h post-meal '
     'via protein gluconeogenesis and fat-delayed gastric emptying. '
     'Warsaw Method: ~1 FPU → Normal bolus with 3h absorption window. '
     'Typical for: eggs, yoghurt with nuts, light pasta with chicken.',
     'Normal',
     3.0,   -- 3h per Warsaw Method for 1 FPU
     2);

-- Pattern 7: Moderate FPU (2 FPU, 4 h)
-- Triggered by meals with significant fat AND protein (e.g. meat with cheese, salmon with rice).
-- Warsaw: 2 FPU → extend bolus over 4 h.
INSERT INTO glycemic_response_patterns
    (pattern_name, gi_min, gi_max, gl_min, gl_max, min_fat_grams, min_protein_grams,
     has_fiber_barrier, curve_description, bolus_strategy, suggested_duration_hours, meal_sequencing_priority)
VALUES
    ('Moderate FPU',
     NULL, NULL,
     NULL, NULL,
     15.0,  -- fat ≥ 15g
     18.0,  -- protein ≥ 18g  (combined ≈ 2 FPU: 15×9+18×4=135+72=207 kcal)
     FALSE,
     'Moderate protein-fat load causes a noticeable secondary glucose rise at 2–4h '
     'driven by fat-slowed gastric emptying and protein gluconeogenesis. '
     'Warsaw Method: ~2 FPU → Normal bolus with 4h absorption window. '
     'Typical for: meat with cheese sauce, salmon with rice, omelette with avocado.',
     'Normal',
     4.0,   -- 4h per Warsaw Method for 2 FPU
     2);
