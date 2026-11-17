-- Fiasp's action curve, refitted to its euglycemic-clamp profile in adults with T1D:
-- 50% of the maximum glucose-lowering effect at ~36 min, maximum effect (GIRmax) at ~122 min,
-- effect over by 5-6 h. The previous 55 min / 4 h curve (the OpenAPS ultra-rapid default) put
-- half the effect in by ~14 min and the peak at 55 min, so a meal dose acted well before the
-- meal did and forecasts dipped low that the user does not see.
-- With the exponential IOB curve, peak 120 min / 5 h gives 50% effect at 32 min and the peak at
-- 120 min; the duration is the shortest that reaches the 5-6 h offset. Total effect per unit is
-- unchanged (that is the ISF).
UPDATE insulin_catalog
   SET peak_minutes = 120,
       dia_hours    = 5.0,
       description  = 'Ultra-rapid bolus: exponential IOB curve fitted to the clamp profile - 50% effect ~36 min, '
                   || 'peak effect ~2 h, over by 5-6 h. Half-life of absorbed insulin ~50-60 min.'
 WHERE code = 'FIASP';
