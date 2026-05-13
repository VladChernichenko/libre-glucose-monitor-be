-- Glycemic response pattern library.
-- Maps meal nutritional parameters (GI, GL, fat, protein, fiber) to expected CGM curve
-- shape and insulin bolus strategy for Type 1 Diabetes management.
--
-- Bolus strategies follow the Warsaw Method for fat-protein units (FPU):
--   1 FPU = 100 kcal from fat + protein combined.
--   ≥3 FPU → Extended bolus ~5h; ≥4 FPU → Dual Wave 30/70 split over 8h.
-- Meal sequencing priority: 1 = eat first (fiber/vegetables), 3 = eat last (simple carbs).

CREATE TABLE glycemic_response_patterns (
    id                          SERIAL PRIMARY KEY,
    pattern_name                VARCHAR(64)  NOT NULL UNIQUE,
    gi_min                      INTEGER,                      -- minimum GI triggering this pattern (NULL = no constraint)
    gi_max                      INTEGER,                      -- maximum GI (NULL = no upper bound)
    gl_min                      NUMERIC(5,1),                 -- minimum glycemic load
    gl_max                      NUMERIC(5,1),
    min_fat_grams               NUMERIC(5,1),                 -- fat threshold in grams (NULL = not relevant)
    min_protein_grams           NUMERIC(5,1),                 -- protein threshold in grams
    has_fiber_barrier           BOOLEAN      NOT NULL DEFAULT FALSE, -- fiber >5g present → blunted absorption
    curve_description           TEXT         NOT NULL,
    bolus_strategy              VARCHAR(32)  NOT NULL CHECK (bolus_strategy IN ('Normal', 'Extended', 'Dual Wave')),
    suggested_duration_hours    NUMERIC(4,1) NOT NULL,        -- expected glucose elevation window (hours)
    meal_sequencing_priority    SMALLINT     NOT NULL CHECK (meal_sequencing_priority BETWEEN 1 AND 3),
    -- 1 = eat first (slows absorption), 2 = middle, 3 = eat last (reserve fast carbs)
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- ─── Seed data ───────────────────────────────────────────────────────────────

-- Pattern 1: Fast Spike
-- Source: GI ≥70 = "High GI" per WHO/FAO (1998); GL ≥20 = high GL (Harvard Health).
-- Bolus: pre-meal pause 15–20 min recommended in ADA Standards of Care 2024 §7.
-- Meal sequencing: high-GI carbs eaten last (Group 3) to reduce spike amplitude.
INSERT INTO glycemic_response_patterns
    (pattern_name, gi_min, gi_max, gl_min, gl_max, min_fat_grams, min_protein_grams,
     has_fiber_barrier, curve_description, bolus_strategy, suggested_duration_hours, meal_sequencing_priority)
VALUES
    ('Fast Spike',
     70, NULL,   -- GI ≥ 70 (high GI threshold)
     20.0, NULL, -- GL ≥ 20 (high GL threshold)
     NULL, NULL, -- fat/protein not the driver
     FALSE,
     'Sharp rise within 30–60 min post-meal; steep peak followed by rapid fall. '
     'Typical for white bread, glucose drinks, sugary cereals.',
     'Normal',
     2.5,        -- glucose elevation resolves within ~2.5h for pure fast carbs
     3);         -- eat these carbs LAST (Group 3) per meal-sequencing protocol

-- Pattern 2: Slow Climb
-- Source: GI ≤55 = "Low GI" per FAO/WHO; consistent with Glycemic Index Foundation classification.
-- Bolus: inject immediately before eating (no pre-bolus pause needed for low-GI foods).
INSERT INTO glycemic_response_patterns
    (pattern_name, gi_min, gi_max, gl_min, gl_max, min_fat_grams, min_protein_grams,
     has_fiber_barrier, curve_description, bolus_strategy, suggested_duration_hours, meal_sequencing_priority)
VALUES
    ('Slow Climb',
     NULL, 55,   -- GI ≤ 55
     NULL, NULL,
     NULL, NULL,
     FALSE,
     'Gradual rise peaking at 1.5–2h; lower amplitude than fast-GI foods. '
     'Typical for legumes, pasta al dente, most whole grains.',
     'Normal',
     3.5,
     2);         -- middle priority in meal sequence

-- Pattern 3: Double Wave
-- Source: Warsaw Method (Pańkowska et al., Pediatric Diabetes 2009 & 2012):
--   Foods with ≥4 FPU (fat+protein ≥400 kcal combined) require Dual Wave bolus
--   30% immediate + 70% extended over 8h to cover biphasic glucose rise.
--   Pizza, burgers, creamy pasta are classic triggers.
INSERT INTO glycemic_response_patterns
    (pattern_name, gi_min, gi_max, gl_min, gl_max, min_fat_grams, min_protein_grams,
     has_fiber_barrier, curve_description, bolus_strategy, suggested_duration_hours, meal_sequencing_priority)
VALUES
    ('Double Wave',
     NULL, NULL,
     NULL, NULL,
     40.0,  -- fat ≥ 40g triggers significant FPU load (≈ 4 FPU when combined with protein)
     25.0,  -- protein ≥ 25g (gluconeogenesis contributes ~50% of amino-acid glucose equivalent)
     FALSE,
     'Biphasic curve: first peak from carbohydrates at 1–2h; second rise at 3–5h '
     'driven by fat-delayed gastric emptying and protein gluconeogenesis. '
     'Classic presentation after pizza, burgers, creamy pasta. '
     'Warsaw Method: ≥4 FPU → Dual Wave 30% now / 70% over 8h.',
     'Dual Wave',
     8.0,   -- 8h coverage per Warsaw Method for ≥4 FPU meals
     2);

-- Pattern 4: Flat Plateau
-- Source: Warsaw Method for 3 FPU (fat+protein 300–399 kcal): Extended bolus ~5h.
--   High-protein low-carb meals (e.g. steak, eggs, cheese) produce slow steady rise
--   without acute spike — gluconeogenesis peaks 3–4h post-meal.
INSERT INTO glycemic_response_patterns
    (pattern_name, gi_min, gi_max, gl_min, gl_max, min_fat_grams, min_protein_grams,
     has_fiber_barrier, curve_description, bolus_strategy, suggested_duration_hours, meal_sequencing_priority)
VALUES
    ('Flat Plateau',
     NULL, NULL,
     NULL, 10.0, -- low GL (low-carb meal)
     NULL,
     30.0,       -- protein ≥ 30g (3+ FPU range)
     FALSE,
     'No acute carb spike; sustained plateau 5–8h from protein gluconeogenesis. '
     'Typical for high-protein low-carb meals: steak, eggs, cheese, nuts. '
     'Warsaw Method: ~3 FPU → Extended bolus over 5h.',
     'Extended',
     5.0,        -- 5h per Warsaw Method for 3 FPU
     2);

-- Pattern 5: Blunted Curve
-- Source: Fiber barrier effect documented in Jenkins et al. (Lancet 1981) and
--   Weickert & Pfeiffer (J Nutr 2008): soluble fiber >5g reduces peak BG by 25–40%
--   and delays time-to-peak by 30–45 min via viscosity slowing gastric emptying.
--   Meal sequencing: vegetables/fiber eaten FIRST (Group 1) maximises blunting effect
--   (Shukla et al., Diabetes Care 2015 — food order study).
INSERT INTO glycemic_response_patterns
    (pattern_name, gi_min, gi_max, gl_min, gl_max, min_fat_grams, min_protein_grams,
     has_fiber_barrier, curve_description, bolus_strategy, suggested_duration_hours, meal_sequencing_priority)
VALUES
    ('Blunted Curve',
     NULL, NULL,
     NULL, NULL,
     NULL, NULL,
     TRUE,  -- fiber >5g present → has_fiber_barrier = true
     'Reduced peak amplitude (−25–40%) and delayed time-to-peak (+30–45 min) '
     'caused by soluble fiber increasing gastric viscosity. '
     'Eat fiber-rich foods FIRST (Group 1 meal sequencing) to maximise blunting. '
     'Adjust bolus timing: inject 0–5 min before eating instead of 15–20 min pre-bolus.',
     'Normal',
     4.0,
     1);   -- eat FIRST in meal sequence (Group 1 — fiber/vegetables always first)

-- ─── Index ───────────────────────────────────────────────────────────────────
CREATE INDEX idx_grp_gi_gl ON glycemic_response_patterns (gi_min, gi_max, gl_min, gl_max);
CREATE INDEX idx_grp_fiber ON glycemic_response_patterns (has_fiber_barrier);
