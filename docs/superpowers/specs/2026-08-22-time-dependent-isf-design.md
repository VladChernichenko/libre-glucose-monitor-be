# Time-dependent ISF — design

- Date: 2026-08-22
- Scope: `glucose-monitor-be` only
- Status: **implemented** on `feat/time-dependent-isf`; this document was corrected after the whole-branch review (see *Reachability*)
- Sub-project **1 of 3** arising from the 21 Aug forecast investigation

## Problem

Insulin sensitivity is configured per meal window — `isf_breakfast`, `isf_lunch`, `isf_dinner`, `isf_night` — and the iOS Settings screen presents all four as live. `UserSettingsDTO.getEffectiveIsf(LocalDateTime)` already resolves them correctly.

`InsulinCalculatorService` does not call it. It reads the base `settings.getIsf()` at two points:

- **line 257**, `resolveGramsPerUnit` → `gramsPerUnit = 10 × isf / carbRatio`, the meal-dose divisor
- **line 284**, `calculateRecommendedInsulin` → `correctionDose = (current − target) / isf`

So any dose this service computes is priced at the base ISF regardless of the hour, while the Settings screen implies otherwise.

The same defect appears a second time, in a different service. `ContextAggregatorService` computes an estimated correction dose — `(latestGlucose − 6.5) / isf − activeIob` — on the base ISF, and `SafetyAndScoringService` renders it to the patient at priority `high` as "Correction guidance estimate is ~Xu". Same formula and same 6.5 mmol/L target as `InsulinCalculatorService`'s correction leg, on a different ISF.

### Reachability — corrected

An earlier revision of this spec claimed the app "would have recommended 16 u" on 21 Aug. **That was false, and the correction matters more than the original claim.**

Nothing in this product recommends an insulin dose to the user:

- `/api/insulin/calculate` is gated behind `shouldUseBackend("insulin-calculator")` and has **no callers** — a search across `glucose-monitor-fe` and `glucose-monitor-iphone` returns nothing.
- The web frontend's `carbsOnBoard.ts` carried a `calculateRecommendedInsulin`, but it too had no callers; the only occurrences of `recommendedInsulin` were inside its own body. It has since been deleted.
- iOS has no bolus calculator at all — only the four Settings fields that make a user expect window ISF to be in force.

So the 16 u figure was arithmetic over stored settings, not a number the app has any path to display. The user dosed by their own judgement, and at 10 u against the 10.7 u their dinner ISF implies, judged it about right.

**What is actually reachable** is the second occurrence: the AI correction-guidance card is rendered via `/api/ai-insights/retrospective`. On 21 Aug at 20:07, glucose 14.1 against the 6.5 target, that card would have shown **7.6 u** where the dinner ISF gives **5.1 u** — a 49 % overstatement, presented at `high` priority to a user whose evening ended at **3.8 mmol/L**.

### Why fix the unreachable paths too

Two of the three sites this spec changes are dead today. They are still worth fixing, for reasons that are about risk rather than present behaviour:

- A plausible-looking dose function that is quietly wrong is a trap. Someone reaches for it precisely when they want to ship a dose surface, and inherits the defect at the moment it starts mattering. The frontend copy was deleted for this reason; the backend endpoint is corrected so that it is the right basis if a dose surface is ever wanted.
- The direction of the error is always the same. Whenever a window override exceeds the base, the base-ISF calculation **over-doses**. There is no configuration in which this defect errs safe.

The honest summary: this work fixes one live patient-facing inconsistency and corrects two paths before they can become live. It is not, on its own, the fix for what went wrong on 21 August — see *Known limitations*.

## Goals

- Every dose figure the backend computes — recommended or merely *displayed as guidance* — uses the ISF in force at that figure's own time.
- No path silently substitutes a single ISF where a window value exists.
- Remove a latent inconsistency in the ODE where one ISF divides a multi-ISF sum.

## Non-goals

- Personalising the four windows. They are user-set or seeded; making them *diverge from measurement* is sub-project 3 (the observational ISF loop, which has produced nothing for this user — all four windows NULL, dinner at 1.6 of the 7.0 weighted samples required).
- The absorption modelling that caused the 4.6-vs-14.0 forecast miss. That is sub-project 2.
- Any schema change.

## Decisions

| Question | Decision |
|---|---|
| Unset window ISF | Fall back to the base `isf` — `getEffectiveIsf`'s existing behaviour |
| Base `isf` column | **Retained.** It is the fallback, so it must exist |
| `HovorkaParameters.isf` | Removed from the ODE; the x3 bridge takes Σ(activity rate) instead |
| Schema / migration | None. No `V1` edit, no `flyway repair` |

### On the fallback

An earlier draft dropped the base column so the compiler would find every reader. That was reversed: a default must exist, and a default needs somewhere to live. The consequence is that a user with no overrides still gets one ISF everywhere — the behaviour is *correct* but not yet *personalised*. What differentiates the four windows is sub-project 3.

The upside of retaining it is real: no `V1__baseline_schema.sql` edit means no `flyway repair` against a populated production database, removing the only operationally risky step this work would have carried.

## Architecture

### 1 · Dosing reads the effective ISF

Both call sites in `InsulinCalculatorService` change from `settings.getIsf()` to `settings.getEffectiveIsf(t)`.

`t` comes from `InsulinCalculationRequest.clientTimeInfo` — the field already exists and already carries `toLocalDateTime()` — falling back to `LocalDateTime.now()` when absent. Resolving on the *client's* clock matters: meal windows are wall-clock boundaries, and a server in a different zone would price an 18:00 dinner bolus as lunch. This mirrors how `GlucoseCalculationsService` already treats `ClientTimeInfo` as authoritative.

**The refusal path is unchanged and still correct.** `getEffectiveIsf` returns null when both the window override and the base are unset; `resolveGramsPerUnit` already refuses with `SETTINGS_INVALID` on a null ISF. Dosing continues to refuse rather than guess. Only the *source* of the number changes, never the strictness.

`gramsPerUnit`'s existing 3–30 g/U envelope is a refusal boundary, not a clamp, and stays exactly as it is. Note it will now be evaluated against a different ISF per window, so a user whose windows differ widely could pass the envelope at one hour and be refused at another — that is the envelope working as intended, catching a mutually inconsistent ISF/carb-ratio pair.

### 2 · Remove the ISF scalar from the ODE

`HovorkaOdeSolver` line 300 approximates plasma insulin from the insulin effect:

```java
double plasmInsulin = (p.isf() * p.effectiveInsulinVolume() > 0)
        ? insulinEffect / (p.isf() * p.effectiveInsulinVolume()) * V_I_SCALE
        : 0.0;
```

But the caller already builds that effect as a per-dose sum, each dose carrying the ISF of the window it was given in (`HovorkaGlucosePredictionService:374`):

```java
insulinEffect += dose.isf() * pAdj.effectiveInsulinVolume() * iobActivityRate;
```

So the division is a single ISF applied to a sum of differently-priced terms. When every dose shares one ISF the factors cancel exactly and `plasmInsulin = Σ(rate) × V_I_SCALE`. When they differ — precisely the case this project is now creating more of — the result is an ISF-weighted blend with no physiological meaning.

**Change:** pass `Σ(iobActivityRate)` to `step()` alongside `insulinEffect`, and compute `plasmInsulin = insulinActivityRate × V_I_SCALE`. The ISF disappears from the bridge by cancellation rather than by choosing a window for it. `HovorkaParameters.isf` then has no reader in the solver.

Whether the field leaves the `HovorkaParameters` record entirely is an implementation detail for the plan — it is still referenced by `HovorkaParameterService.applyScales` for the twin's `isfScale`, and untangling that is only worth doing if it falls out cleanly. The binding requirement is that **the solver no longer divides by it**.

**Bit-identity is the acceptance bar.** For any forecast where all active doses share one ISF — which is every forecast today for a user without window overrides — the emitted curve must be byte-identical to before. This is the repo's highest-risk file and this exact bar has been met on it before (Task 3b's 15,120-point comparison), so it is a known-achievable standard, and this time the proof gets committed rather than discarded.

### 3 · The AI correction-guidance card — the only live path

Added after the whole-branch review found it; it was absent from this spec's first revision, which is why the spec originally misidentified where the defect actually reached a user.

`ContextAggregatorService` resolves the ISF for its correction estimate with `getEffectiveIsf(end)`, where `end` is the instant the analysis window closes — the same "now" already used for `activeCob`, `activeIob` and `predicted2h`, and reported as the context's `windowEnd`. That matches `InsulinCalculatorService`, which resolves at the dose time rather than at the CGM reading's own timestamp, so the two paths converge on identical semantics rather than merely both being window-aware.

`SafetyAndScoringService` is unchanged: it renders whatever figure the aggregator produces. Fixing the source rather than the renderer keeps one definition of the correction dose.

### 4 · Experiments

`ExperimentService.saveIsfToSettings` writes an `ISF_ONE_UNIT` result to the base `isf`. Left alone. With the base retained as the fallback, writing a measured ISF there is coherent — it improves the value every unset window inherits. Redirecting it to the window the experiment ran in belongs with sub-project 3, where per-window divergence is the actual subject.

## Data flow

```
POST /api/ai-insights/retrospective          [the live path]
  └─ ContextAggregatorService: end = analysis "now"
       └─ getEffectiveIsf(end) → estimatedCorrectionUnits
            └─ SafetyAndScoringService renders it at priority high

POST /api/insulin/calculate                  [no clients today]
  └─ InsulinCalculationRequest.clientTimeInfo → t   (falls back to now)
       └─ settings.getEffectiveIsf(t)
            ├─ window override for MealWindow.fromTimestamp(t), if set
            └─ else base user_settings.isf
                 └─ null → SETTINGS_INVALID refusal (unchanged)

Forecast (unchanged behaviour, simplified internals)
  └─ buildDoseActivities → getEffectiveIsf(dose time) per dose   [already correct]
       └─ insulinEffect = Σ(dose.isf × 2·VG × rate)
       └─ NEW: insulinActivityRate = Σ(rate) → solver's x3 bridge
```

## Error handling

| Case | Behaviour |
|---|---|
| Window override set | Used |
| Window override null, base set | Base used |
| Both null | `SETTINGS_INVALID` refusal — unchanged |
| `clientTimeInfo` absent | `LocalDateTime.now()`; server zone, documented as a fallback |
| Derived `gramsPerUnit` outside 3–30 | `INSULIN_PARAMS_INCONSISTENT` refusal — unchanged, now per-window |

## Testing

**Dosing**
- A request timestamped in the dinner window with `isf_dinner = 1.5`, `isf = 1.0`, `carbRatio = 2.0`, 80 g carbs yields **10.7 u**, not 16.0 u. Those are the 21 Aug stored settings and carb load — though, per *Reachability*, no surface would have shown either figure.
- The same request timestamped at 23:00 with `isf_night = 1.0` yields 16.0 u — proving the result actually tracks the clock rather than having been rebased on a different constant.
- Correction dose at 14.1 against a 6.5 target resolves per window.
- Both-null still refuses with `SETTINGS_INVALID`.
- Mutation check: reverting either call site to `getIsf()` must fail these tests.

**AI correction card (the live path)**
- Same glucose and IOB at two times of day in different windows yields two different `estimatedCorrectionUnits`.
- Mutation check: reverting `ContextAggregatorService` to `getIsf()` must fail that test and no other.

**ODE**
- Committed golden-curve fixture: a single-dose, no-override forecast emits values identical to the pre-change implementation.
  Note the achieved guarantee is narrower than "byte-identical" as first written. Shim callers are exact by construction; the production path is empirical plus a bounded error, because `p.isf()` is `settings.isf × isfScale` for digital-twin users, so the factors never cancelled exactly even in exact arithmetic. Bounded at roughly 5e-3 mmol/L against a 0.1 mmol/L emission quantum.
- The accumulator `insulinActivityRate += iobActivityRate` needs a test reaching raw `x3`, not emitted glucose: the bridge is sub-quantum, so both golden curves pass even when that line is written `dose.isf() * iobActivityRate` — the exact defect being removed.
- A two-dose forecast with *different* window ISFs exercises the path where old and new genuinely diverge, with the new value asserted against the hand-computed `Σ(rate) × V_I_SCALE`.
- All existing `che.glucosemonitorbe.hovorka.*` tests pass unmodified.

`InsulinCalculatorService` is inside the JaCoCo 80 % line-coverage rule. The gate passed at 1129 tests / 0 failures at the branch point and at **1142 / 0** as shipped.

## Known limitations

**This does not fix the forecast.** The 21 Aug miss — 4.6 predicted against ~14.0 actual — was caused by absorption modelling: pattern matching never running for client-supplied nutrition profiles, and the dashboard having no meal-specific fat delay. Nothing here changes the curve. This sub-project makes the *dose arithmetic* right.

**Most of what this fixes is not currently reachable.** Only the AI correction-guidance card is live. The dose calculator this spec is named after has no clients. That is a deliberate accepted state, not an oversight — see *Reachability* — but it means the branch should not be described at merge as fixing the 21 August incident.

**The ODE change has no observable effect at therapeutic doses.** The x3 bridge contributes roughly 5e-3 mmol/L against a 0.1 mmol/L emission quantum, so no forecast moves. Its value is that the old error scaled with `max(dose.isf) / p.isf()` and the schema bounds window overrides only at `> 0`: a base of 0.1 with an `isf_dinner` of 3.0 amplifies the plasma-insulin driver 30×, which does clear the quantum. It removes an amplifier that was bounded only by users not configuring that combination.

**Four identical windows are still one number.** Seeding or leaving windows unset means the fallback carries every hour. Correct, but flat.

**Two further ISF reads remain on the base value**, both deliberate: `HovorkaParameterService` builds `p.isf()` from the base for the twin's `isfScale`, and `ExperimentService` writes an `ISF_ONE_UNIT` result there. Both are coherent while the base is the fallback every unset window inherits.

## Related

- `docs/superpowers/specs/2026-08-20-hypo-rescue-carb-logging-design.md` — the timezone and dual-computation precedents this follows
- Sub-project 2: absorption modelling (pattern matching, meal-specific fat delay, meal splitting)
- Sub-project 3: the learning loops (verification titration and observational ISF, both currently inert for this user)
