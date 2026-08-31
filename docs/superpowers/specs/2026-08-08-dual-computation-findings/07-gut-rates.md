## Finding: the effective gut rates are derived in three places, bound by none

**Quantity:** the effective gastric/intestinal rate constants at a step — `kGriEff`, `kMaxEff`,
`kMinEff`, `kAbsEff` — and the `Ra` computed from them.

`DallaManGutModel` itself is a pure function library with a single definition of each primitive.
The duplication is in its callers, which each re-derive the same four effective values from the
same constants using separately-written expressions.

**Paths:**
- `hovorka/HovorkaOdeSolver.java:348-364` (`derivatives`) — `tHalfMeal = p.tMaxG() × 1.68`,
  `cCal = caloricScale(tHalfMeal)`, `giScale(gi)`, then the three emptying rates, `kAbsEff` from
  `effectiveKAbs(p.tMaxG())`, `kempt`, and `kemptEff = kempt × ilealBrake(inc)`.
- `hovorka/HovorkaGlucosePredictionService.java:586-598` (the warm-up replay) — the same nine
  lines, hand-written, from `activeTMaxG` and `activeGi`.
- `hovorka/HovorkaGlucosePredictionService.java:443-444` — a partial third derivation for
  display: `kAbsDisplay = effectiveKAbs(pStep.tMaxG()) × giScale(state.activeGI())`, then
  `gutModel.ra(state.qgut(), kAbsDisplay)`, shipped as
  `PredictionPointDTO.carbAbsorptionEffect`.

**The deltas, and which are real.** The replay's comment at `:585-586` claims it is "identical to
derivatives(), which derives them from `p.tMaxG()`". Diffing them:

| | `derivatives` | warm-up replay | verdict |
|---|---|---|---|
| half-life factor | bare literal `1.68` (:349) | `HovorkaParameterService.HALF_LIFE_TO_TMAX_G` (:588) | **same value, unpinned** — see below |
| `cCal` / `kAbsEff` source | `p.tMaxG()` | `activeTMaxG` (rescue-aware, :581-593) | **deliberate**, not a defect — the replay integrates *past* meals including rescue carbs, whose `tMaxG` differs per entry. The comment overstates parity. |
| `kEmpt` meal reference | `mealMmol` from `s0.mealMmol()` (:251) | `dRef`, refreshed as `qsto1 + qsto2` (:565) and stored into the state at `:614` | **equivalent** — `dRef` *is* the state's `mealMmol`, maintained manually where `step()` carries it |
| empty-stomach guard | `kEmpt` returns `kMinEff` when `mealMmol ≤ 0` (`DallaManGutModel:80`) | `dRef > 0 ? kEmpt(…) : 0.0` (:596) | **benign** — the two differ, but only when `dRef ≤ 0`, in which case `qsto2 ≈ 0` and `kemptEff × qsto2` is zero either way |

So the four candidate deltas resolve to: one deliberate difference the comment misdescribes, two
non-issues, and one genuine unpinned duplication.

**The unpinned duplication.** `HovorkaParameterService.HALF_LIFE_TO_TMAX_G = 1.68` (:70) is used
at eight sites across the codebase; `HovorkaOdeSolver:349` is the one that hardcodes the literal
instead. Identical today. Change the named constant — a plausible calibration edit — and the ODE
silently keeps integrating at 1.68 while the replay, `MacroNutrientGastricModel`,
`PredictionReplayEngine` and `GlucosePredictService` all move. The two halves of the same
prediction would then disagree about how fast a meal empties, with no test failing.

**Nothing binds the derivations.** `DallaManGutModelTest` asserts the model functions in
isolation (`kEmpt` at `:41-73`, `caloricScale` from `:160`); `GutModelMealTypeTest`,
`HovorkaRescueAbsorptionTest` and `HovorkaGlucosePredictionServiceTest` exercise behaviour
through the public path. None asserts that `derivatives` and the replay produce the same four
rates for the same inputs. Each derivation is free to drift alone.

**Reachability:** `predictionPath` and `PredictionPointDTO.carbAbsorptionEffect`.
**Confidence:** confirmed-by-reading.
**Status:** open (latent — paths agree at `8b5d8c3`, nothing enforces it). Task 10 attempts the
pin; see `10-pinning-tests.md` for whether the strong behavioural form was reachable without the
refactor this plan forbids.

**Obvious follow-up, deliberately not taken here:** extracting one shared helper for the four
rates would collapse all three derivations. That is a production change to the ODE's hot path and
belongs in its own test-first plan, not in an audit.
