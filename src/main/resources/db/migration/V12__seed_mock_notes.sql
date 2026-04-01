INSERT INTO notes (
    id,
    user_id,
    timestamp,
    carbs,
    insulin,
    meal,
    comment,
    glucose_value,
    detailed_input,
    insulin_dose,
    mock_data,
    created_at,
    updated_at
)
SELECT
    gen_random_uuid(),
    u.id,
    v.ts,
    v.carbs,
    v.insulin,
    v.meal,
    v.comment,
    v.glucose_value,
    v.detailed_input,
    v.insulin_dose,
    TRUE,
    NOW(),
    NOW()
FROM users u
CROSS JOIN (
    VALUES
        (NOW() - INTERVAL '11 hours', 45.0, 4.0, 'Breakfast', 'MOCK: oatmeal + banana', 7.2, '45g oatmeal 4u', '{"type":"bolus","units":4}'),
        (NOW() - INTERVAL '7 hours', 65.0, 6.0, 'Lunch', 'MOCK: pasta + salad', 9.1, '65g pasta 6u', '{"type":"bolus","units":6}'),
        (NOW() - INTERVAL '4 hours', 0.0, 2.0, 'Correction', 'MOCK: correction after rise', 10.3, '2u correction', '{"type":"correction","units":2}'),
        (NOW() - INTERVAL '2 hours', 20.0, 0.0, 'Snack', 'MOCK: snack during activity pause', 6.4, '20g snack; paused workout', '{"type":"none","units":0}')
) AS v(ts, carbs, insulin, meal, comment, glucose_value, detailed_input, insulin_dose)
WHERE NOT EXISTS (
    SELECT 1
    FROM notes n
    WHERE n.user_id = u.id
      AND n.mock_data = TRUE
);

