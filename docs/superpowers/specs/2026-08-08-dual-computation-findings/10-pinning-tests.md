## Pinning tests

`src/test/java/che/glucosemonitorbe/hovorka/DuplicatedConstantPinningTest.java`. Four assertions,
all **green** at `8b5d8c3` — they pin the status quo, they do not report bugs. Full suite after
adding them: **1147 tests, 0 failures, 0 errors, 4 skipped**.

| Pin | Binds | Finding | Strength |
|---|---|---|---|
| `peakX3Basal_matchesItsDerivation` | `PEAK_X3_BASAL` ↔ `1 − F01_PER_KG/EGP0_PER_KG` | `06-basal-egp.md` | **strong** — behavioural over the actual relationship; `within(0.005)` covers only the documented 2 dp rounding (0.39752 → 0.40) |
| `duplicatedConstants_agreeAcrossServices` | `DEFAULT_CARB_RATIO`, `DEFAULT_ISF`, `PRE_BOLUS_MAX_TIMING_EFFECT` across `GlucoseCalculationsService` and `ContextAggregatorService` | `05-context-aggregator.md` C | **strong** — exact equality, read reflectively from both private declarations |
| `preBolusTimingContribution_identicalAcrossServices` | the two `calculatePreBolusTimingContribution` bodies | `05-context-aggregator.md` C | **strong** — behavioural equality over 10 inputs crossing every branch (`null`, 0, 5, 9.9, 10, 20, 25, 25.1, 45, 100) |
| `halfLifeToTMaxG_pinnedAtItsPublishedValue` | `HALF_LIFE_TO_TMAX_G == 1.68` | `07-gut-rates.md` | **weak — value-only.** See below. |

### Why the gut-rate pin is weak, and what it would take to strengthen it

The strong form would assert that `HovorkaOdeSolver.derivatives` and the warm-up replay produce
the same four effective rates for identical inputs. It is **not reachable** from a test:

- `HovorkaOdeSolver.derivatives` (:326) is package-private, so a test in
  `che.glucosemonitorbe.hovorka` *can* call it.
- The replay's derivation (`HovorkaGlucosePredictionService:586-598`) is inline inside the
  private `buildWarmState` (:483). There is no seam to call it through, and creating one is a
  production change to the ODE warm-up path, which this plan's no-refactor rule forbids.

So the pin degrades to asserting the named constant still equals `1.68`. That catches the
realistic failure — someone retunes `HALF_LIFE_TO_TMAX_G` and the ODE's hardcoded literal at
`HovorkaOdeSolver:349` silently stays behind — but it does **not** catch the ODE's literal being
changed instead, and it does not verify the two derivations agree on anything else.

**To close it properly:** extract the four-rate derivation into one package-private helper called
by both `derivatives` and `buildWarmState`, then assert equality through it. That is the
`07-gut-rates.md` follow-up, and it collapses the duplication rather than merely observing it.

### Not pinned

- **`06-basal-egp.md`, the EGP double-suppression question.** Status `needs user input` — there is
  no correct behaviour to pin until someone says whether post-bolus re-suppression of an
  already-suppressed `egp0` is intended. Pinning current behaviour here would freeze a possible
  bug.
- **`08-plasma-insulin.md`, `V_I_SCALE = 12.0`.** Nothing to bind it *to*: the constant's
  counterpart is a PK compartment that does not exist in the codebase, and its provenance is
  recorded only as "empirical". A value-only pin would assert `12.0 == 12.0`.
- **Findings A and B of `05-context-aggregator.md`, and findings 02, 03, 04.** These are live
  divergences, not agreeing duplications. A test that pins them would pin the defect. They need
  fixes, which is what the tracked issues are for.
