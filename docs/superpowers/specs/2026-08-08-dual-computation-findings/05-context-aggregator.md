## Finding A: the AI advisor ships its own 2-hour prediction

**Quantity:** predicted glucose 2 hours ahead, and the correction dose implied by it.

**Paths:**
- `ai/ContextAggregatorService.java:109` —
  `predicted2h = clamp(latest + (activeCob/10)×carbRatio − activeIob×isf + preBolusTimingContribution, 1.0, 25.0)`
- the Hovorka `predictionPath` that produces `twoHourPrediction` and the rendered chart
  (see `02-factors-block.md` for the other three).

That expression is the analytical model `30b76bd` removed from the dashboard headline, still
running in a second service — same `carbRatio` pricing, same `1.0, 25.0` clamp as the dead
`calculatePredictedGlucose:407`. `estimatedCorrectionUnits` (:102) is a second such quantity:
`max(0, (latest − 6.5)/isf − activeIob)`, with the target hardcoded at
`CORRECTION_TARGET_MMOl = 6.5` (:33).

**Reachability: patient-facing, and LLM-facing.** Unlike the `factors` block, which is serialized
but unrendered, this one is displayed and reasoned from:
- `ai/SafetyAndScoringService.java:56` emits it as the `PREDICTION_2H` pattern —
  "Model 2h prediction is X mmol/L."
- `:71` emits `estimatedCorrectionUnits` as `CORRECTION_MATH_GUIDE` at priority **high** —
  "Correction guidance estimate is ~Nu using current settings and active insulin."
- `ai/LlmGatewayService.java:425` and `:461` inject both into the LLM prompt, so the model
  reasons from them as ground truth and can restate them in prose.
- `ai/RagRetrieverService.java:29-30` uses their presence to select retrieved guidance.

So a number that disagrees with the chart is shown to the patient, used to justify a correction
dose, and fed to a language model that will repeat it. This is the defect class `30b76bd` fixed,
surviving on a path that renders more prominently than the one that was fixed.

**Disagreement scenario:** the dashboard chart and the advisor disagree whenever `carbRatio`
misprices the meal, which F12 established is always, since `carbRatio` is inert on the ODE. With
COB decaying 40 g over the horizon and `carbRatio = 2.0`, this model adds `+8.0 mmol/L` from
carbs alone; the ODE's carb rise is set by `aG` and `tMaxG` and is unrelated to that number.

**Confidence:** confirmed-by-reading.
**Status:** open — the most consequential finding in this audit.

## Finding B: a second, lossy note→`CarbsEntry` converter

`ai/ContextAggregatorService.java:142` (`toCarbsEntry`) builds entries with `id`, `timestamp`,
`carbs`, `insulin`, `mealType`, `comment`, `glucoseValue`, `originalCarbs`, `userId` and the
rescue marker — but **no** `estimatedGi`, `glycemicLoad`, `fiber`, `protein`, `fat`,
`absorptionSpeedClass`, `suggestedDurationHours` or `absorptionMode`, all of which
`service/nutrition/NoteToCarbsEntryMapper:28` populates from the note's stored `nutritionProfile`.
`GlucoseCalculationsService:57` and `GlucosePredictService:93` both inject that shared mapper;
this service does not.

`CarbsOnBoardService.calculateRemainingCarbs` reads exactly those fields (`:49`, `:54`, `:65`),
so a nutrition-profiled meal decays as a plain default entry for the advisor and as a GI-aware
curve for the dashboard — the same divergence as `03-confidence-cob-iob.md`, in a second service.

The javadoc at `:134-140` records a bug this split already caused: the advisor reported a 15 g
rescue still ~11 g on board while the dashboard had it three-quarters absorbed. That was fixed by
adding `RescueCarbProfile.mark` here too (`:154`) — i.e. by patching the copy rather than
adopting the mapper, which is why the nutrition fields are still missing.

**Reachability:** live — `activeCob` (:94) feeds `predicted2h` in Finding A and is displayed.
**Confidence:** confirmed-by-reading. **Status:** open.

## Finding C: duplicated constants and a duplicated method body

Declared privately in **both** `ContextAggregatorService` and `GlucoseCalculationsService`:
`DEFAULT_CARB_RATIO = 2.0` (:31 / :38), `DEFAULT_ISF = 1.0` (:32 / :39),
`PRE_BOLUS_MAX_TIMING_EFFECT = 1.2` (:35 / :49).

`calculatePreBolusTimingContribution` exists in both (`:189` / `:455`). Diffing the two bodies
with comments and blank lines stripped yields **no differences**: identical thresholds
(10.0 / 25.0), identical coefficients (`0.6 + delta × 0.6`, `delta × 0.6`), identical clamping.

Nothing binds any of these. They agree today; a change to one is invisible to the other.

**Reachability:** latent — the values match at `8b5d8c3`.
**Confidence:** confirmed-by-reading.
**Status:** open (latent — paths agree at `8b5d8c3`, nothing enforces it). Pinned by Task 10.

## Window and ISF resolution

- **Window.** This service selects notes over `[end − windowHours, end]` (`:54`, `:71`), with
  `windowHours` supplied by the caller (`ai/AiInsightService:24, 33, 55`), against
  `activeCobIobInputs`'s fixed 8-hour lookback (`GlucoseCalculationsService.getRecentNotes:561`).
  For `windowHours < 8` the advisor computes COB/IOB from strictly fewer notes than the dashboard
  and will report lower values for the same instant, with nothing reconciling them. Folded into
  Finding B rather than filed separately — it is the same "different inputs, same engine" shape.
- **ISF.** `:99-101` resolves `cob.getEffectiveIsf(end)` with a `DEFAULT_ISF` fallback. The claim
  in its comment holds: this is the window-aware resolver, matching the C2 fix (`9b6ccd4`). Note
  that `VerificationService:204` is now the only path still substituting base ISF
  (`04-experiment-verification.md`). The `DEFAULT_ISF` fallback here is unreachable for the same
  reason as `01-isf-fallback.md`.

## Observed but out of scope

`CORRECTION_TARGET_MMOl = 6.5` (:33) is a hardcoded correction target with no counterpart
constant in `GlucoseCalculationsService`. The time-dependent-ISF final review (C1) found
`glucose-monitor-fe/src/services/carbsOnBoard.ts` using **7.0** for the same purpose. Cross-repo,
so out of scope per the backend-only constraint — recorded, not dropped. The constant name also
carries a typo (`MMOl`).
