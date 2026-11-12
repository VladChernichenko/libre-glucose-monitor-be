# Mathematical model review — backend (2026-11-12)

Scope: `glucose-monitor-be` only (not the `-watch` / `-assistant` copies).
Method: code read against the literature retrieved from PubMed. No tests or simulations were run; the numbers below are hand-calculated.

**Summary:** the core equations are implemented correctly, but the per-user calibration around them is partly broken and several physiological assumptions are not supported by the papers they cite.

## What matches the literature

- **Hovorka glucose subsystem.** F01 (0.0097 mmol/kg/min), EGP0 (0.0161), VG (0.16 L/kg), k12 (0.066 /min), the F01 reduction below 4.5 mmol/L and the renal term above 9 mmol/L match Hovorka 2004 ([DOI](https://doi.org/10.1088/0967-3334/25/4/010)).
- **Dalla Man gut model.** k_gri = k_max = 0.0558, k_min = 0.008, k_abs = 0.057, f = 0.90, b = 0.82, d = 0.010 and the tanh gastric-emptying formula match Dalla Man 2006/2007 ([DOI](https://doi.org/10.1109/TBME.2006.883792), [DOI](https://doi.org/10.1109/TBME.2007.893506)).
- **Numerics.** The oref0 exponential IOB curve, the 1.68 half-life factor for the two-compartment chain, the RK4 step and the Huber/MAD weighting are correct.
- **Protein.** The delayed rise from about 2 h is consistent with Paterson 2016/2017, who saw later glucose from 150–300 min ([DOI](https://doi.org/10.1111/dme.13011), [DOI](https://doi.org/10.1111/dme.13347)). The magnitude (0.2 g carb per g protein) is on the low side of their 75 g result.

## Findings

### Calibration (digital twin)

> **Status, 2026-11-12:** findings 1–3 are fixed in the working tree (not committed). The ISF scale is now applied per dose in `HovorkaGlucosePredictionService` only, and replay passes its candidate scale explicitly; replay reads the new unrounded `rawPredictedGlucose`; replay builds the same meal entries as the dashboard (GI, fiber, protein, fat, `ProteinGluconeogenesis`), with `macroTMaxG` off by default. Findings 4–10 are open. Stored twins were fitted with the old replay and should be recalibrated.

#### 1. `isfScale` has no effect during calibration replay
`src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java:775`

The dose ISF comes from `settings.getEffectiveIsf()`, which returns `settings.isf` whenever it is set. The scaled `p.isf()` is only a fallback and is never read, so the calibrator's `isfScale` cannot change a replayed prediction.

Example: a user with isf = 2.0 whose real sensitivity is 3.0. `replay(isfScale=1.0)` and `replay(isfScale=1.5)` return identical samples (the `NONE` provider gives isfScale 1.0, and `settings.isf` overrides the fallback). The Jacobian column is zero, the ridge keeps isfScale at 1.0, and the stored twin never corrects insulin sensitivity. If `settings.isf` is null, the live path instead applies the scale twice (`buildForUser` and line 776).

#### 2. The LM Jacobian is computed on predictions rounded to 0.1
`src/main/java/che/glucosemonitorbe/hovorka/learning/PredictionReplayEngine.java:212`

Calibration residuals use `PredictionPointDTO.predictedGlucose`, which is rounded to 0.1 mmol/L, while `LmParameterFitter` takes forward differences with a 0.001 step.

Example: a sample with d(pred)/d(agScale) = 3 mmol/L moves 0.003 mmol/L under the step, below the 0.05 rounding half-width. Most Jacobian entries are exactly 0 and a few are ±100. LM either stalls at the start point (no personalisation) or steps on noise; the agScale and egpScale fits are not reproducible.

#### 3. Calibration replays a different meal model than live
`src/main/java/che/glucosemonitorbe/hovorka/learning/PredictionReplayEngine.java:271`

| | Replay | Live |
|---|---|---|
| Fat/protein glucose | FPU entry including fat, at +90 min | protein only, at +120 min |
| Gut time constant | overridden by `MacroNutrientGastricModel` | user's own, slowed by fiber |
| GI, protein, fat, fiber on entries | absent | present (GI scaling, ileal brake) |

Example: a 60 g carb / 30 g protein / 30 g fat dinner. Replay adds 33 g of carb-equivalent (fat 27 g + protein 6 g) at +90 min and a 73-min tMaxG; live adds 6 g at +120 min and brakes emptying instead. `agScale`, the hourly residual grid and the σ band are fitted to the replay model's errors and then applied to the live model. `ProteinGluconeogenesis` itself records that the fat term caused a +1.1 mmol/L overshoot.

#### 4. The ridge prior is not scaled to the sample count
`src/main/java/che/glucosemonitorbe/hovorka/learning/DigitalTwinCalibrator.java:274`

One residual row of √0.05 × (scale − 1) per parameter is appended to several hundred to a thousand error rows in mmol/L.

Example: 250 anchors × 4 horizons with 2 mmol/L errors give a data sum of squares near 4000; moving agScale from 1.0 to the 2.0 clamp costs 0.05. A weakly identified scale runs to the 0.5/2.0 bound on logging noise, and only the 2 % validation gate stands between it and live predictions.

### Physiology

#### 5. Basal below 0.3 U/kg forecasts a steady fasting rise
`src/main/java/che/glucosemonitorbe/hovorka/BasalInsulinResolver.java:103`

EGP suppression is scaled by loggedUnits / (0.3 U/kg), so any titrated basal dose below that population reference leaves net EGP above F01. Split doses are combined with max, not sum.

Example: a 70 kg user on 14 U glargine (0.2 U/kg) with stable fasting glucose. Fraction 0.667, x3 = 0.267, EGP 0.826 vs F01 0.679 mmol/min, net +0.147 mmol/min over 2·VG = 22.4 L: +0.39 mmol/L per hour, about +1.6 mmol/L at 4 h. A user on 2 × 10 U detemir is treated as 10 U. Logging no basal at all gives a flat forecast.

#### 6. Dalla Man mixed-meal rates are treated as a pure-carb baseline
`src/main/java/che/glucosemonitorbe/hovorka/HovorkaOdeSolver.java:268`

The published rate constants were identified on a mixed meal ([DOI](https://doi.org/10.1109/TBME.2006.883792), [DOI](https://doi.org/10.1109/TBME.2007.893506)). The code multiplies all four by GI/100 with a default GI of 70, then by the protein/fat ileal brake down to 0.5.

Example: a meal logged with no GI estimate absorbs at 0.7× the published rates. With 30 g protein and 30 g fat the emptying rate falls toward 0.35×, so k_min becomes 0.0028 /min (stomach half-time about 4 h in the plateau phase). The peak is predicted too low and too late, and the tail too high.

#### 7. The macro gastric model is not supported by its citations
`src/main/java/che/glucosemonitorbe/hovorka/MacroNutrientGastricModel.java:93`

- Elashoff's β is a curve-shape (lag) parameter, not a multiplier on half-emptying time.
- "9 + 27.5 × kcal/mL" matches Calbet & MacLean 1997 ([DOI](https://doi.org/10.1113/jphysiol.1997.sp021881)): 600 mL liquid meals at 0.1–0.7 kcal/mL. That paper also found the nature of the calories plays a minor role. Here it is applied to solids at a fixed 250 mL.
- The cited "Calvert 1989" and "Palumbo (2026)" could not be found. The nearest Palumbo paper is a 2023 type 2 diabetes model ([DOI](https://doi.org/10.1016/j.compbiomed.2023.107158)).

Example: a 630 kcal mixed meal. Density is taken as 2.52 kcal/mL (3.6× beyond the fitted range), t½ = 78 min, then ×1.57 for the β ratio = 123 min, tMaxG 73 min. This runs in the calibration replay (`macroTMaxG = true`), so it shapes the fitted twin scales.

#### 8. CGM lag is applied twice, and τ = 12 min is above the measured lag
`src/main/java/che/glucosemonitorbe/hovorka/InterstitialLagModel.java:57`

The plasma state is seeded with the CGM reading, which is already the lagged interstitial value, and the output is then low-pass filtered again. Tracer studies measured 5–6 min in healthy adults and a median 6.8 min in T1D (Basu 2013/2015, [DOI](https://doi.org/10.2337/db13-1132), [DOI](https://doi.org/10.1177/1932296814554797)).

Example: glucose rising 0.1 mmol/L/min after a meal. True plasma is about 0.7–1.2 mmol/L above the CGM value, but Q1 starts at the CGM value and the filter holds the curve a further ~12 min behind. The 15–30 min forecast under-predicts a rise and over-predicts during a fall toward hypo.

### Other estimators

#### 9. Meal-window ISF ignores insulin already on board
`src/main/java/che/glucosemonitorbe/service/IsfMealWindowProfileService.java:219`

Only boluses after t0 reject the window. A bolus shortly before t0 still acts through the whole window but is not added to `units`. Meal boluses divide by (absorbed/ICR − units), which is near zero when the bolus matched the meal.

Example: 4 U at 12:00 and a 1 U correction at 12:30. The 12:30 event sees glucose fall 6 mmol/L by 17:00, attributes it to 1 U and records ISF = 6.0 (true 1.2). For a well-matched meal bolus (60 g at 10 g/U vs 5.8 U) the denominator is 0.2 U, so 0.5 mmol/L of CGM noise becomes an ISF of 2.5 of either sign.

#### 10. The empirical engine uses one person's ISF and carb ratio
`src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java:378`

`buildEmpiricalPath` always passes `EmpiricalTwinParams.defaults()` (ISF 27 mg/dL/U, CR 7 and 10 g/U, hand-tuned on about 5 days of one person) and never reads the user's settings.

Example: a user with ISF 3.0 mmol/L/U and 15 g/U who selects EMPIRICAL logs 4 U. The path falls 6 mmol/L instead of 12, and 60 g of carbs rises 9–13 mmol/L instead of 12. That path supplies the 2 h / 4 h headline, the trend chip and the pre-bolus pause search.

## Smaller points

- **Exercise tail.** The post-exercise sensitivity tail has a 120 min half-life, but McMahon 2007 found glucose requirements raised again 7–11 h after exercise ([DOI](https://doi.org/10.1210/jc.2006-2263)). The boost also only multiplies bolus insulin, so it does nothing when IOB is zero.
- **Fat.** Fat only delays absorption in the live model. Pańkowska 2012 reports a mixed fat-protein meal raising glucose at 4–6 h ([DOI](https://doi.org/10.1089/dia.2011.0083)).
- **Fiber in COB.** The enhanced COB curve subtracts fiber from carbs while the ODE uses full carbs, so displayed COB and the forecast disagree for the same meal.
- **Residual bias.** The hourly bias pools the 30–120 min horizons into one value and applies it unchanged out to 8 h.

## References (retrieved from PubMed)

| Paper | DOI |
|---|---|
| Hovorka et al. 2004, nonlinear MPC of glucose in T1D | [10.1088/0967-3334/25/4/010](https://doi.org/10.1088/0967-3334/25/4/010) |
| Dalla Man, Camilleri, Cobelli 2006, oral glucose absorption model | [10.1109/TBME.2006.883792](https://doi.org/10.1109/TBME.2006.883792) |
| Dalla Man, Rizza, Cobelli 2007, meal simulation model | [10.1109/TBME.2007.893506](https://doi.org/10.1109/TBME.2007.893506) |
| Dalla Man, Rizza, Cobelli 2006, mixed meal simulation model | [10.1109/IEMBS.2006.260810](https://doi.org/10.1109/IEMBS.2006.260810) |
| Calbet & MacLean 1997, caloric content and gastric emptying | [10.1113/jphysiol.1997.sp021881](https://doi.org/10.1113/jphysiol.1997.sp021881) |
| Hunt, Smith, Jiang 1985, meal volume and energy density | [10.1016/0016-5085(85)90650-x](https://doi.org/10.1016/0016-5085(85)90650-x) |
| Basu et al. 2013, plasma-to-interstitial lag in humans | [10.2337/db13-1132](https://doi.org/10.2337/db13-1132) |
| Basu et al. 2015, plasma-to-interstitial lag in T1D | [10.1177/1932296814554797](https://doi.org/10.1177/1932296814554797) |
| Paterson et al. 2016, protein alone in T1D | [10.1111/dme.13011](https://doi.org/10.1111/dme.13011) |
| Paterson et al. 2017, protein dose-response with carbohydrate | [10.1111/dme.13347](https://doi.org/10.1111/dme.13347) |
| Pańkowska et al. 2012, fat-protein meal in T1D | [10.1089/dia.2011.0083](https://doi.org/10.1089/dia.2011.0083) |
| McMahon et al. 2007, biphasic glucose requirement after exercise | [10.1210/jc.2006-2263](https://doi.org/10.1210/jc.2006-2263) |
| Palumbo et al. 2023, macronutrient absorption and exercise model | [10.1016/j.compbiomed.2023.107158](https://doi.org/10.1016/j.compbiomed.2023.107158) |
