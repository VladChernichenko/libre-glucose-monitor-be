-- Rapid-insulin durations were the wrong way round. An ultra-rapid analog is absorbed faster and
-- has the shorter tail; a standard rapid analog acts longer. One unit lowers glucose by the same
-- total amount on either - only the action curve differs. Peaks are unchanged (55 / 75 min).
UPDATE insulin_catalog
   SET dia_hours   = 4.0,
       description = 'Ultra-rapid bolus: exponential IOB curve, peak ~55 min, duration ~4 h. Half-life ~50-60 min.'
 WHERE code = 'FIASP';

UPDATE insulin_catalog
   SET dia_hours   = 4.0,
       description = 'Ultra-rapid bolus: exponential IOB curve, peak ~55 min, duration ~4 h. Half-life ~44 min.'
 WHERE code = 'LYUMJEV';

UPDATE insulin_catalog
   SET dia_hours   = 4.5,
       description = 'Rapid bolus: exponential IOB curve, peak ~75 min, duration ~4.5 h. Half-life ~40-50 min.'
 WHERE code = 'APIDRA';

UPDATE insulin_catalog
   SET dia_hours   = 4.5,
       description = 'Rapid bolus: exponential IOB curve, peak ~75 min, duration ~4.5 h. Half-life ~80 min.'
 WHERE code = 'NOVORAPID';

UPDATE insulin_catalog
   SET dia_hours   = 4.5,
       description = 'Rapid bolus: exponential IOB curve, peak ~75 min, duration ~4.5 h. Half-life ~60 min.'
 WHERE code = 'HUMALOG';
