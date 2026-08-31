## Traced, no divergence found: `confidence`

`GlucoseCalculationsService.calculateConfidence` (:514) has exactly one call site (:149) and no
counterpart anywhere in `src/main` — `grep -rn "calculateConfidence("` returns the declaration
and that one call, nothing else. It is a self-contained heuristic over entry counts and the
current glucose reading. Single source of truth; nothing to reconcile.

## Finding: ISF titration computes COB from nutrition-stripped entries

**Quantity:** carbs-on-board for a given note at a given time.

Every caller uses the same engine — `CarbsOnBoardService.calculateTotalCarbsOnBoard` — so the
divergence is not in the maths. It is in **what each caller feeds it**, and one caller strips the
fields the maths reads.

**Paths:**
- `service/GlucoseCalculationsService.java:239` (`activeCobIobInputs`) builds entries via
  `convertNoteToCarbsEntry` (:569) → `service/nutrition/NoteToCarbsEntryMapper.toCarbsEntry`
  (:28), which populates `estimatedGi`, `glycemicLoad`, `fiber`, `protein`, `fat`,
  `absorptionSpeedClass`, `absorptionMode`, `suggestedDurationHours` and `patternName` from the
  note's stored `nutritionProfile` JSON, and applies `RescueCarbProfile.mark` to hypo treatments
  (:44-46). Notes come from an 8-hour window (`getRecentNotes:561`).
- `service/IsfMealWindowProfileService.java:299` (`buildCarbsEntry`) builds its own entry with
  `id`, `timestamp`, `carbs`, `insulin`, `mealType`, `originalCarbs`, `userId` and a defaulted
  `absorptionMode` — **and nothing else**. No GI, no macros, no speed class, no suggested
  duration. Notes come from an event-scoped window, `[t0 − 30 min, tEnd]` (:228).

**Why the stripped fields change the number:** `CarbsOnBoardService.calculateRemainingCarbs`
reads exactly those fields — `getSuggestedDurationHours()` (:49) and
`getAbsorptionSpeedClass()` (:54) set `maxDuration`, and `:65` branches on
`"GI_GL_ENHANCED".equalsIgnoreCase(entry.getAbsorptionMode())` to pick the curve. A note carrying
a nutrition profile is therefore modelled as a slow, GI-aware absorption by the dashboard and as
a plain default decay by ISF titration — same note, same engine, different curve.

**Disagreement scenario:** a meal note with a stored nutrition profile setting
`absorptionMode = GI_GL_ENHANCED` and `suggestedDurationHours = 6`. At `tEnd` the dashboard
reports the meal still substantially on board; `IsfMealWindowProfileService` sees a
default-decay entry that has largely cleared, so `cobAtEnd` is lower, `absorbedGrams =
carbsGrams − cobAtEnd` is higher, `deltaCarbMmol` is higher, and
`insulinAttributedDrop = deltaCarbMmol − observedDelta` is inflated. The derived
`isf = insulinAttributedDrop / units` therefore comes out **too high** — the titration credits
insulin with clearing carbs the dashboard says had not yet arrived.

**Reachability:** live and dosing-relevant. The estimate feeds `isf_meal_window_snapshots`
(`V7__isf_meal_window_snapshots.sql`), which `UserSettingsDTO.getEffectiveIsf` reads, which is
what prices a correction bolus. This is not a display divergence.

**Not a defect in the window choice.** The `[t0 − 30 min, tEnd]` scope and the deliberate
`n.isHypoTreatment()` exclusion (:233-236) are correct for the question this service asks —
"what was this bolus's nutrient envelope" is not "what is on board now", and the rescue-carb
exclusion is documented and right. The finding is only that the entries are built by a second,
lossy builder instead of the shared mapper.

**Confidence:** confirmed-by-reading.
**Status:** open.

## Survey: every COB/IOB computation site

| Caller | Lines | Routes through `activeCobIobInputs`? |
|---|---|---|
| `GlucoseCalculationsService` | 129, 132, 140, 141, 297, 298, 321, 322 | owns it (:239) |
| `ExperimentService` | 71, 72 (via `inputs`), 117, 118 (own list) | partly — see `04-experiment-verification.md` |
| `ai/ContextAggregatorService` | 94, 95 | no — see `05-context-aggregator.md` |
| `IsfMealWindowProfileService` | 245 | no — the finding above |
