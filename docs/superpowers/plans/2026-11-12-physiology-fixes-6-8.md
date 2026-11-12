# Physiology Fixes 5–8 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish findings 5–8 of the model review: a usual basal dose stays full coverage whatever time it is injected, an ordinary mixed meal absorbs at the published Dalla Man rates, the unsupported liquid-meal half-time is gone, and a forecast requested without a CGM trend still applies the sensor lag once.

**Architecture:** Five tasks in `glucose-monitor-be`, each with its own test cycle. Most of findings 5–8 is already in the uncommitted working tree; this plan repairs one defect in that work (the basal reference), finishes what it left open (the ileal brake, the half-time, callers that send no trend), and first makes the test suite match the changes already made.

**Tech Stack:** Java 21, Spring Boot, Gradle, JUnit 5, AssertJ, Mockito.

**Spec:** `glucose-monitor-be/docs/math-model-review-2026-11-12.md`, findings 5–8. Its status note is out of date; the table below is the current state.

## What this revision changes

The previous version of this plan had three errors. They are fixed here.

- **Brake.** It recentred `ilealBrake` on `INC_REF = 390 / 590 = 0.661`. `Inc` never gets there: for a 390 kcal load it peaks at 0.46 after about 100 minutes. No meal would ever have been slowed, and the reference meal would have run up to 1.56× the published rates. Task 3 replaces it.
- **Finding 5.** It said "do not touch". The implemented `usualDailyUnits` doubles the reference when two daily doses fall in the same 24-hour block. Task 2 fixes it.
- **Lag.** Its detector change did nothing in `detect`: the anchor is the first reading of the loaded window, so there is no earlier reading to take a slope from. The real gap is elsewhere: the web dashboard and every server-side forecast send no trend. Task 5 covers those; the detector is out of scope (see the end).

It also missed that the working tree already changed `giScale` and the lag constant without updating the tests that pin them. Task 1 handles that.

## State of the working tree

Nothing below is committed. HEAD is `c9d3e07` (findings 1–3).

| Finding | In the tree | Left to do |
|---|---|---|
| 5 basal | Coverage is measured against the user's own usual daily dose; doses add; a silent log stays at F01 | The usual-dose estimate breaks on dose-time jitter — Task 2 |
| 6 gut rates | `giScale = GI / 70`, so a meal with no GI runs at the published rates | The brake still slows every meal with macros; documented as "Known over-count" — Task 3 |
| 7 gastric model | The β multiplier is gone; `macroTMaxG` is off in calibration replay | `computeTMaxG` still runs in `BacktestHarness` (default on) and `CGMacrosBacktestTest` — Task 4 |
| 8 CGM lag | τ = 7 min; plasma is seeded from reading + τ × trend; dashboard (iPhone) and replay pass a trend | Web client and server-side callers pass none — Task 5 |

Two design choices in this revision are judgment calls. Each is stated in its task with the alternative that was rejected:

- Task 3: the brake counts only protein and fat beyond the reference meal's share. It does not speed up leaner meals.
- Task 5: the server derives the trend from stored CGM when a request carries none.

## Global Constraints

- Scope is `glucose-monitor-be` only. Do not edit `glucose-monitor-be-watch`, `glucose-monitor-be-assistant`, the web client, or the iOS apps.
- The working tree holds 22 uncommitted files of earlier work. Do not revert, stash, or reformat them. Do not commit unless the user asks; if they do, one commit per task.
- Commands run from `glucose-monitor-be`. Gradle compiles the whole test source set, so a test that references a method that does not exist yet stops every other test from running. Each task orders its steps around that.
- TDD: write the failing test, run it, confirm it fails for the stated reason, then make the smallest production change.
- A test that pins a number may be re-pinned only when the task says so and names the cause. Never flip the direction of an inequality or widen a tolerance to get a test through. If a test fails for a reason the task does not name, stop and report it.
- `HovorkaOdeSolver.DEFAULT_GI` stays 70 and `giScale(gi) = clamp(gi / 70, 0.3, 1.5)`.
- `InterstitialLagModel.PHYSIOLOGICAL_TAU_MIN` stays 7.0 and `MAX_TREND_MMOL_PER_MIN` stays 0.15. Do not fit τ.
- `KAPPA_GLP1`, `K_M_PF`, `K_INC_PF`, `K_DEL` and `K_PF_DRAIN` keep their values. `ilealBrake(inc)` keeps its formula.
- `PEAK_X3_BASAL`, `WANE_HOURS`, `LOOKBACK_HOURS` and `suppressionCurve` keep their values.
- Protein glucose (`ProteinGluconeogenesis`, replay `fpuEquiv`) is not touched.

## File structure

| File | Change |
|---|---|
| `hovorka/BasalInsulinResolver.java` | `usualDailyUnits` groups doses by spacing, not by 24-hour blocks counted from now |
| `hovorka/HovorkaOdeSolver.java` | New `REFERENCE_PF_KCAL_PER_CARB_G` and `brakeKcal(carbsG, proteinG, fatG)`; comment on `KAPPA_GLP1` |
| `hovorka/HovorkaGlucosePredictionService.java` | `protFatKcal(entry)` delegates to `brakeKcal`; three stale comments |
| `hovorka/MacroNutrientGastricModel.java` | Loses `computeTMaxG` and its constants; keeps fiber and bolus strategy |
| `hovorka/learning/PredictionReplayEngine.java` | Loses the `macroTMaxG` switch |
| `assistant/common/CgmFreshnessService.java` | New `freshTrendMmolPerMin(userId, now)` |
| `service/GlucoseCalculationsService.java` | Hovorka path falls back to the stored trend when the request has none |
| `src/test/.../backtest/BacktestHarness.java`, `BacktestHarnessTest.java`, `CGMacrosBacktestTest.java` | No `macroTMaxG`; CGMacros runs the live gut mechanisms |
| Test classes named in each task | The red step, and the re-pinned golden curves |

All main paths are under `src/main/java/che/glucosemonitorbe/`, all test paths under `src/test/java/che/glucosemonitorbe/`.

## Review Focus

Each line is pinned by a test in the task named.

- Two once-daily doses 23.5 hours apart must not read as one 28 U day. Task 2.
- A twice-daily regimen (doses 12 hours apart) must still add up to one day. Task 2 keeps `splitDosesAddUp` green.
- A meal logged with no macros at all (`protein` and `fat` null) must forecast exactly as it does today. Task 3.
- A carb-free first course (eggs, then the pasta 20 minutes later) must still slow the carbs that follow. Task 3.
- A fibrous meal must still absorb more slowly after `computeTMaxG` is deleted. Task 4.
- A request with no trend and no fresh stored reading must forecast exactly as it does today. Task 5.
- A trend sent by the client must win over the stored one. Task 5.

---

### Task 1: Make the suite match the changes already in the tree

The tree changed `giScale` from `gi / 100` to `gi / 70` and τ from 12 to 7 minutes. These test files were not updated and pin the old numbers. This was established by reading the code, not by running the tests; Step 1 confirms it.

**Files:**
- Modify: `src/test/.../hovorka/HovorkaOdeSolverTest.java` (`glp1Inhibition_zeroInc_noEffect`)
- Modify: `src/test/.../hovorka/HovorkaRescueAbsorptionTest.java` (`GOLDEN_GLUCOSE`, `GOLDEN_CARB_EFFECT`)
- Modify: `src/test/.../hovorka/HovorkaGoldenCurveTest.java` (the four golden arrays)
- Modify: `src/test/.../hovorka/HovorkaGlucosePredictionServiceTest.java` (one comment)
- No production file.

**Interfaces:**
- Produces: a green baseline for `hovorka`, `glycemic`, `service` and `backtest` tests, and the CGMacros report table that Task 4 compares against.

- [ ] **Step 1: Run the baseline and record every failure**

Run: `./gradlew test --tests 'che.glucosemonitorbe.hovorka.*' --tests 'che.glucosemonitorbe.glycemic.*' --tests 'che.glucosemonitorbe.service.*' --tests 'che.glucosemonitorbe.backtest.*' -i`

Expected failures:
- `HovorkaOdeSolverTest.glp1Inhibition_zeroInc_noEffect` — expects a drain of 3.9 mmol (`K_MAX × 0.70`); the tree drains about 5.4.
- `HovorkaRescueAbsorptionTest.ordinaryMealCurveMatchesTheGoldenValues` — its past meal has GI 55, which now scales 0.79 instead of 0.55.
- `HovorkaGoldenCurveTest.goldenCurveWithoutActivity` and `goldenCurveWithActivityNote` — a default-GI meal now scales 1.0 instead of 0.7, and the sensor filter is 7 minutes instead of 12.

Save the `CGMacros Postprandial Backtest` table printed by `CGMacrosBacktestTest.cgMacrosPostprandialAccuracy` (it runs when `/Users/vlad/IdeaProjects/glucose-monitor-project/CGMacros` exists, which it does on this machine).

Any other failure: decide by one rule. If the test pins a literal that moved because of `gi / 70` or τ = 7, list it and re-pin it in Step 3. Otherwise stop and report it; do not edit it.

- [ ] **Step 2: Fix the solver test's expectation**

In `glp1Inhibition_zeroInc_noEffect`, replace the `giScale = 70.0 / 100.0` line and the assertion with:

```java
// qsto = 100 (full) -> kempt ≈ K_MAX × giScale; inc = 0 -> brake 1; activeGI = 70 -> giScale 1
assertThat(100.0 - next.qsto2())
        .isCloseTo(100.0 * DallaManGutModel.K_MAX * HovorkaOdeSolver.giScale(70), within(0.5));
```

Update the two comment lines above it that say `giScale=0.70` and `≈ 3.906`.

- [ ] **Step 3: Re-pin the golden curves**

In `ordinaryMealCurveMatchesTheGoldenValues`, before the loop, temporarily add:

```java
System.out.println("GLUCOSE " + curve.stream().map(p -> String.valueOf(p.getPredictedGlucose())).toList());
System.out.println("CARB " + curve.stream().map(p -> String.valueOf(p.getCarbAbsorptionEffect())).toList());
```

Run the class, read the two lines from `build/test-results/test/TEST-che.glucosemonitorbe.hovorka.HovorkaRescueAbsorptionTest.xml` (`<system-out>`), replace both arrays, and remove the prints. Change the arrays' javadoc: it says the values come from commit `4ceae15`; add that they were regenerated on 2026-11-12 for `giScale = GI / 70`.

Do the same in `HovorkaGoldenCurveTest.assertGolden` for `GOLDEN_GLUCOSE_NO_ACTIVITY`, `GOLDEN_GLUCOSE_WITH_WALK` and `GOLDEN_CARB_EFFECT`, printing `getPredictedGlucose` and `getCarbAbsorptionEffect` per branch. `GOLDEN_INSULIN_EFFECT` must not need a change; if it does, stop and report.

Before pasting, check the new curves against the old ones by eye: the meal's rise must come earlier and the 4-hour value must be within 1 mmol/L of the old one. A curve that changed direction is a bug, not a re-pin.

In `HovorkaGlucosePredictionServiceTest.highFatTMaxG_glucosePeakShiftsRight`, the comment says `GI=70 default (giScale=0.70)`. Change it to say the default GI scales 1.0; leave the 500-minute window.

- [ ] **Step 4: Run the baseline again**

Run: the command from Step 1.

Expected: PASS.

---

### Task 2: The usual basal dose survives dose-time jitter (finding 5)

`usualDailyUnits` sums doses per 24-hour block counted back from now and takes the largest block. Take 14 U at Monday 22:30 and Tuesday 22:00. At Wednesday 22:10 both are between 24 and 48 hours old, so the block holds 28 U, coverage is halved, and the fasting forecast climbs about 0.6 mmol/L per hour until the next dose is logged. At 21:59 and at 22:31 the same log gives 14 U.

**Files:**
- Modify: `src/main/.../hovorka/BasalInsulinResolver.java` (`usualDailyUnits` and its javadoc)
- Test: `src/test/.../hovorka/BasalInsulinResolverTest.java`

**Interfaces:**
- Consumes: the private `hoursSinceDose(Note, LocalDateTime)`.
- Produces: `usualDailyUnits(List<Note>, LocalDateTime)` with the same signature, and `static final double SAME_DAY_HOURS = 18.0`.

- [ ] **Step 1: Write the failing test**

Add to `BasalInsulinResolverTest`:

```java
@Test
@DisplayName("once-daily doses 23.5 h apart are two days, not one doubled day")
void dosesADayApartAreNotAddedTogether() {
    // 22:30 two nights ago and 22:00 last night, read a little after 22:00 tonight.
    Note older = dose(47.6); older.setInsulin(14.0);
    Note newer = dose(24.1); newer.setInsulin(14.0);
    List<Note> log = List.of(older, newer);

    assertThat(resolver.usualDailyUnits(log, NOW)).isEqualTo(14.0);
    assertThat(resolver.resolveEgpSuppression(log, NOW, GLARGINE_H)).isGreaterThan(PEAK * 0.95);
    assertThat(fastingGlucoseAt4h(log, GLARGINE_H)).isCloseTo(6.0, within(0.2));
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests che.glucosemonitorbe.hovorka.BasalInsulinResolverTest`

Expected: `dosesADayApartAreNotAddedTogether` fails with `usualDailyUnits` = 28.0. Every other test in the class passes.

- [ ] **Step 3: Group doses by spacing**

One day's basal is every dose logged within `SAME_DAY_HOURS` after a given dose, that dose included. The usual amount is the largest such group. 18 hours sits between a twice-daily gap (about 12 hours) and a once-daily gap (about 24), so it tolerates six hours of jitter either way.

```java
/** Doses logged less than this many hours after another belong to the same day's basal. */
static final double SAME_DAY_HOURS = 18.0;

public double usualDailyUnits(List<Note> longActingNotes, LocalDateTime now) {
    if (longActingNotes == null) {
        return 0.0;
    }
    double usual = 0.0;
    for (Note first : longActingNotes) {
        double firstHoursAgo = hoursSinceDose(first, now);
        if (Double.isNaN(firstHoursAgo)) continue;
        double total = 0.0;
        for (Note other : longActingNotes) {
            double hoursAgo = hoursSinceDose(other, now);
            if (Double.isNaN(hoursAgo)) continue;
            double hoursAfterFirst = firstHoursAgo - hoursAgo;
            if (hoursAfterFirst >= 0 && hoursAfterFirst < SAME_DAY_HOURS) total += other.getInsulin();
        }
        usual = Math.max(usual, total);
    }
    return usual;
}
```

Rewrite the javadoc to describe this rule. Keep its last two sentences (why the largest; 0 for an empty log). Add one line on the known limit: two full doses taken within 18 hours of each other (a late dose, then the next one on time) read as a double day until the earlier one leaves the lookback.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests che.glucosemonitorbe.hovorka.BasalInsulinResolverTest`

Expected: PASS, including `splitDosesAddUp` (10 U + 10 U, 12 hours apart → 20) and `usualDailyUnitsIsTheLargestDay` (21 U and 7 U, 24 hours apart → 21).

---

### Task 3: Only protein and fat beyond the reference meal brake emptying (finding 6)

The Dalla Man rates were identified on a mixed meal of 10 kcal/kg with 45% of energy from carbohydrate, 15% from protein and 40% from fat ([Dalla Man 2004](https://doi.org/10.1152/ajpendo.00319.2003), [Basu 2003](https://journals.physiology.org/doi/full/10.1152/ajpendo.00190.2001)). They already contain the slowing from that much protein and fat: 55/45 of the carbohydrate energy, which is `4 × 55 / 45 = 4.89` kcal of protein+fat per gram of carbohydrate. The brake must therefore be driven by what a meal carries beyond that, not by all of it.

**Decision:** the excess is taken per logged entry, in kcal, before it enters the `protFatGut` compartment. The ODE, `ilealBrake`, `dIncDt` and the state vector do not change. The rejected alternative is a two-sided brake that also speeds up meals leaner than the reference. It needs a second `Inc` trajectory for the reference meal (two more state variables, and every `new HovorkaState(...)` in the tests), and there is no dataset here to set its size: CGMacros subjects make their own insulin, and the T1D sets carry no macros. Lean fast meals are already covered by the GI scale (up to 1.5×) and the rescue profile.

What it does, from a Python replica of the solver (50–80 g carbs eaten 30 minutes ago, no insulin):

| Meal | Braking load today | After | Lowest brake today → after | 2 h glucose below the same carbs alone, today → after |
|---|---|---|---|---|
| 80 g carbs, 30 g protein, 30 g fat | 390 kcal | 0 | 0.68 → 1.00 | 1.05 → 0 |
| 60 g, 30 g, 30 g (the review's example) | 390 | 97 | 0.68 → 0.84 | 0.80 → 0.38 |
| 60 g, 20 g, 15 g | 215 | 0 | 0.75 → 1.00 | 0.62 → 0 |
| 50 g, 40 g, 60 g | 700 | 456 | 0.63 → 0.67 | 0.80 → 0.71 |
| No carbs, 30 g, 30 g (first course) | 390 | 390 | unchanged | unchanged |

**Files:**
- Modify: `src/main/.../hovorka/HovorkaOdeSolver.java` (new constant and method; `KAPPA_GLP1` javadoc; `@param protFatKcalNow` on `step`)
- Modify: `src/main/.../hovorka/HovorkaGlucosePredictionService.java` (`protFatKcal`, about line 1139)
- Test: `src/test/.../hovorka/HovorkaGlucosePredictionServiceTest.java`
- Test: `src/test/.../hovorka/HovorkaOdeSolverTest.java`
- Modify: `src/test/.../hovorka/HovorkaRescueAbsorptionTest.java` (golden arrays, second re-pin)

**Interfaces:**
- Produces: `public static final double HovorkaOdeSolver.REFERENCE_PF_KCAL_PER_CARB_G = 4.0 * 55.0 / 45.0` and `public static double HovorkaOdeSolver.brakeKcal(double carbsG, double proteinG, double fatG)`, returning `max(0, 4 × protein + 9 × fat − REFERENCE_PF_KCAL_PER_CARB_G × carbs)` with negative inputs treated as 0. Task 4 calls `brakeKcal` from `CGMacrosBacktestTest`.

- [ ] **Step 1: Write the failing forecast test**

Add to `HovorkaGlucosePredictionServiceTest`, after `pastMealProteinAndFatReachTheModel`:

```java
@Test
@DisplayName("a meal with the reference meal's share of protein and fat absorbs like its carbs alone")
void referenceMixedMealAbsorbsAtThePublishedRates() {
    // 80 g of carbs carries 391 kcal of protein+fat in the reference meal; 30 g + 30 g is 390.
    List<PredictionPointDTO> plain = curveFor(pastMeal(30, 80, null, null, null));
    List<PredictionPointDTO> mixed = curveFor(pastMeal(30, 80, null, 30.0, 30.0));
    for (int minute : new int[]{30, 60, 120, 240}) {
        assertThat(glucoseAt(mixed, minute)).as("+%d min", minute).isEqualTo(glucoseAt(plain, minute));
    }
}

@Test
@DisplayName("a carb-free protein and fat first course still slows the carbs that follow")
void proteinFatFirstCourseSlowsTheFollowingMeal() {
    CarbsEntry pasta = pastMeal(30, 50, null, null, null);
    CarbsEntry eggs = pastMeal(50, 0, null, 30.0, 30.0);
    double alone = glucoseAt(curveFor(pasta), 120);
    double afterEggs = glucoseAt(service.buildPredictionPath(params, 6.0, NOW,
            List.of(eggs, pasta), List.of(), List.of(), USER_ID, 240), 120);
    assertThat(afterEggs).isLessThan(alone - 0.3);
}
```

In `pastMealProteinAndFatReachTheModel`, change the meal to `pastMeal(30, 50, null, 40.0, 60.0)` and the message to `40g protein + 60g fat`. The test checks that macros reach the warm-up; 30 g + 30 g on 50 g of carbs will be only 146 kcal over the reference and would clear the 0.3 mmol/L bound by a rounding step.

- [ ] **Step 2: Run the tests to verify the first one fails**

Run: `./gradlew test --tests che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionServiceTest`

Expected: `referenceMixedMealAbsorbsAtThePublishedRates` fails at +30 min (the mixed curve is lower). `proteinFatFirstCourseSlowsTheFollowingMeal` and `pastMealProteinAndFatReachTheModel` pass; they are guards.

- [ ] **Step 3: Add the unit test, `brakeKcal`, and use it**

Add to `HovorkaOdeSolverTest`:

```java
@Test
void brakeKcal_countsOnlyProteinAndFatBeyondTheReferenceMeal() {
    double ref = HovorkaOdeSolver.REFERENCE_PF_KCAL_PER_CARB_G;
    assertThat(ref).isCloseTo(4.0 * 55.0 / 45.0, within(1e-12));
    assertThat(HovorkaOdeSolver.brakeKcal(80, 30, 30)).isEqualTo(0.0);
    assertThat(HovorkaOdeSolver.brakeKcal(60, 30, 30)).isCloseTo(390.0 - 60.0 * ref, within(1e-9));
    assertThat(HovorkaOdeSolver.brakeKcal(0, 10, 20)).isEqualTo(220.0);
    assertThat(HovorkaOdeSolver.brakeKcal(50, 0, 0)).isEqualTo(0.0);
    assertThat(HovorkaOdeSolver.brakeKcal(-5, -1, 10)).isEqualTo(90.0);
}
```

In `HovorkaOdeSolver`, add the constant and the method next to `ilealBrake`. The constant's javadoc states the meal composition and the two citations above.

In `HovorkaGlucosePredictionService`, replace the body of `protFatKcal(CarbsEntry)` with a call to `HovorkaOdeSolver.brakeKcal`, passing 0 for a null `carbs`, `protein` or `fat`. Update its javadoc: it returns the braking load, not the meal's protein+fat energy. Both callers (`buildWarmState`, `buildFutureProtFatTimeline`) already skip a load of 0.

Replace the "Known over-count" paragraph on `KAPPA_GLP1` with two sentences: the Dalla Man rates already contain the reference meal's protein and fat, so `protFatGut` is loaded only with the excess from `brakeKcal`; a meal leaner than the reference is not sped up. Change `@param protFatKcalNow` on the master `step` to say "protein+fat load beyond the reference meal (see `brakeKcal`)".

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionServiceTest --tests che.glucosemonitorbe.hovorka.HovorkaOdeSolverTest`

Expected: PASS.

- [ ] **Step 5: Re-pin the one golden curve that carries macros**

`HovorkaRescueAbsorptionTest.goldenScenario` has 60 g carbs with 25 g protein and 20 g fat: 280 kcal, under the 293 kcal reference, so its brake is now off and the curve moves. Re-pin `GOLDEN_GLUCOSE` and `GOLDEN_CARB_EFFECT` with the Task 1 procedure and add "and for the reference-meal brake" to the javadoc. The new curve must rise earlier than the Task 1 one, never later.

- [ ] **Step 6: Run the package**

Run: `./gradlew test --tests 'che.glucosemonitorbe.hovorka.*' --tests 'che.glucosemonitorbe.service.*'`

Expected: PASS. `PredictionReplayEngineTest`'s replay-equals-live test stays green because both sides go through `protFatKcal`. `HovorkaGoldenCurveTest` has no macros and must not move.

---

### Task 4: Remove the liquid-meal half-time (finding 7)

`computeTMaxG` applies a line fitted to 600 mL liquid meals at 0.1–0.7 kcal/mL to solid meals at an assumed 250 mL. Nothing in the live forecast calls it. It still runs by default in `BacktestHarness` and in `CGMacrosBacktestTest`, so those measure a model the user never sees. Delete it; do not replace it with another half-time, which would count the stomach load the ODE already integrates.

**Files:**
- Modify: `src/main/.../hovorka/MacroNutrientGastricModel.java`
- Modify: `src/main/.../hovorka/learning/PredictionReplayEngine.java` (lines 14, 99–104, 142, 222, 323–328, 355–359)
- Modify: `src/test/.../backtest/BacktestHarness.java` (lines 61–62, 288–297, 474–477)
- Modify: `src/test/.../backtest/BacktestHarnessTest.java` (lines 113, 115–119, 150)
- Modify: `src/test/.../backtest/CGMacrosBacktestTest.java` (lines 86–96, 162–171)
- Test: `src/test/.../hovorka/MacroNutrientGastricModelTest.java`
- Comments only: `DallaManGutModel.java` (46–48, 95, 139–144), `HovorkaGlucosePredictionService.java` (213–214, 1080, 1127), `HovorkaParameterService.java` (63, 158), `learning/TwinScales.java` (23–24), `ProteinGluconeogenesis.java` (14–18), `DuplicatedConstantPinningTest.java` (104)

**Interfaces:**
- Consumes: `MacroNutrientGastricModel.fiberViscosityFactor(double)`, `HovorkaOdeSolver.brakeKcal` from Task 3, `HovorkaParameterService.HALF_LIFE_TO_TMAX_G`.
- Produces: no `computeTMaxG`; no `macroTMaxG` field on `PredictionReplayEngine.Config` or `BacktestHarness.Config`.

- [ ] **Step 1: Add the guards that must survive the deletion**

In `MacroNutrientGastricModelTest`, delete the six `computeTMaxG` tests (`noMacros_returnsFallbackTMaxG` through `theSameCaloriesEmptyAtTheSameRateWhateverTheirType`) and the `TMAX_FACTOR` constant. Keep the four `bolusStrategy` tests. Add:

```java
@Test
void fiberViscosity_isOneWithNoFiber_andGrowsExponentially() {
    assertThat(MacroNutrientGastricModel.fiberViscosityFactor(0)).isEqualTo(1.0);
    assertThat(MacroNutrientGastricModel.fiberViscosityFactor(10)).isCloseTo(Math.exp(0.2), within(1e-12));
    assertThat(MacroNutrientGastricModel.fiberViscosityFactor(-3)).isEqualTo(1.0);
}
```

The live path's fiber behaviour is already pinned by `HovorkaFiberAbsorptionTest` (seven tests, including `fiberRichMealAlreadyEaten_isSlowedInTheWarmUp` and `fiberFirstCourse_bluntsTheFollowingMeal`). No new forecast test is needed.

- [ ] **Step 2: Run the guards before deleting anything**

Run: `./gradlew test --tests che.glucosemonitorbe.hovorka.MacroNutrientGastricModelTest --tests che.glucosemonitorbe.hovorka.HovorkaFiberAbsorptionTest`

Expected: PASS. If a `HovorkaFiberAbsorptionTest` test fails, stop: fiber is not reaching the live path and the deletion is the wrong change.

- [ ] **Step 3: Delete the half-time and its callers**

`MacroNutrientGastricModel`: delete `computeTMaxG` and `EMPTYING_INTERCEPT_MIN`, `EMPTYING_DENSITY_COEFF`, `DEFAULT_MEAL_VOLUME_ML`, `FALLBACK_T_HALF_MIN`, `T_HALF_MIN_CLAMP`, `T_HALF_MAX_CLAMP`. Rewrite the class javadoc to cover what is left: the fiber viscosity factor (a calibration choice) and the bolus strategy.

`PredictionReplayEngine`: delete `Config.macroTMaxG` with its javadoc, the `if (cfg.macroTMaxG …)` block, the private `withTMaxG` (the one in this class; `HovorkaGlucosePredictionService` has its own and keeps it), and the `MacroNutrientGastricModel` import. Rename the `AnchorContext` component `macroParams` to `params`, pass `baseParams` when building it, and read `a.params()` at line 222. The locals `domCarbs`, `domProtein`, `domFat` and `domFiber` (lines 270 and 312) fed only the deleted block: delete them. Keep `domCarbAmt` if anything else still reads it.

`BacktestHarness`: delete `Config.macroTMaxG`, the override block (use the base parameters for every anchor), and `macroTMaxG=%s` with its argument in the report header. Delete the same four `dom…` locals (lines 236 and 276) under the same rule, and the import if nothing else uses it.

`BacktestHarnessTest`: delete the three `macroTMaxG` assignments. Variant A's label becomes `A * ODE core (no FPU)`, variant B's `B * production-faithful (FPU)`, and the comment above B drops "per-meal macro tMaxG".

`CGMacrosBacktestTest.cgMacrosPostprandialAccuracy`: run each meal through the mechanisms the live forecast uses.

```java
double tMaxG = 45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G
        * MacroNutrientGastricModel.fiberViscosityFactor(meal.fiberG);
HovorkaParameters params = buildParams(weightKg, tMaxG);
// ...
state = solver.step(state, params, carbMmol, HovorkaOdeSolver.DEFAULT_GI,
        HovorkaOdeSolver.brakeKcal(meal.carbsG, meal.proteinG, meal.fatG), 0.0, 0.0);
```

`syntheticT1d_mealBolus_suppressesPostprandialRise`: `double tMaxG = 45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G;` and delete the unused `proteinG`, `fatG` and `fiberG` locals.

- [ ] **Step 4: Fix the comments that still describe the deleted model**

No comment may say that a meal's macros set `tMaxG`, cite a "Calvert formula", or point at `MacroNutrientGastricModel` on `/api/predict` (that endpoint no longer exists).

- `DallaManGutModel.BASE_T_HALF_MIN`: 45 min is the carb half-life at which the published rates apply unchanged (`caloricScale` = 1). `caloricScale`'s `@param`: the absorbing meal's gut half-time, `tMaxG × 1.68`. `effectiveKAbs`: `tMaxG` comes from the user's carb half-life, slowed by fiber, or from the rescue profile.
- `HovorkaGlucosePredictionService` 213–214: callers that built the parameters themselves (calibration replay, backtests). 1080: "through the fiber viscosity factor". 1127: drop the last clause.
- `HovorkaParameterService` 63: "Public so backtests can reuse it". 158 and `TwinScales` 23–24: `tMaxGScale` is not wired in because the gut time constant is replaced per meal by fiber and rescue carbs.
- `ProteinGluconeogenesis` 14–18: one prediction path; fat's effect is gastric delay, modelled by the GLP-1 ileal brake.
- `DuplicatedConstantPinningTest` line 104: drop `MacroNutrientGastricModel, ` from the assertion message.

Test javadocs that tell the history of `/api/predict` (`HovorkaFiberAbsorptionTest`, `ProteinGluconeogenesisTest`) stay as they are.

- [ ] **Step 5: Run the tests and compare the CGMacros table**

Run: `./gradlew test --tests 'che.glucosemonitorbe.hovorka.*' --tests 'che.glucosemonitorbe.backtest.*' -i`

Expected: PASS, and `rg -n "computeTMaxG|macroTMaxG|Calvert" src` prints nothing.

Put the new CGMacros table next to the Task 1 one in the hand-off. The numbers will move, because the backtest now runs the user's half-life with fiber and the brake instead of a per-meal half-time. If `MAE at +30 min < 2.5` or `Clarke A+B > 50%` fails, stop and report both tables. Do not change the thresholds.

---

### Task 5: A forecast requested without a trend takes it from stored CGM (finding 8)

`integrate` seeds plasma with `reading + 7 × trend` and starts the sensor filter at the reading, which is correct. With trend 0 the two coincide and the forecast is the old double-lag one. The iPhone sends a trend. The web client (`glucoseCalculationsApi.ts`) does not, and neither do `GlucoseAlertService`, `AssistantService`, `SnapshotForecaster`, `PreBolusPauseOptimizer`, `ContextAggregatorService` or the GET endpoint. For those, a fall toward hypo is still forecast about 0.7 mmol/L too high for the first quarter of an hour.

**Decision:** resolve the trend on the server, at the one place every one of those calls passes through, from the stored CGM series. The rejected alternative is to make each caller send a trend: seven call sites, one of them a client outside this plan's scope.

**Files:**
- Modify: `src/main/.../assistant/common/CgmFreshnessService.java`
- Modify: `src/main/.../service/GlucoseCalculationsService.java` (fields near line 96; the Hovorka branch of the private `buildPredictionPath`, lines 414–430)
- Test: `src/test/.../assistant/common/CgmFreshnessServiceTest.java`
- Test: `src/test/.../service/DashboardMealMacrosWiringTest.java`
- Create: `src/test/.../hovorka/InterstitialLagModelTest.java`
- Test: `src/test/.../hovorka/HovorkaGlucosePredictionServiceTest.java`

**Interfaces:**
- Consumes: `CgmFreshnessService.freshReading(UUID, Instant)`, `CgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(UUID, Long, Long)`, the dashboard overload `HovorkaGlucosePredictionService.buildPredictionPath(double currentGlucose, double currentTrendMmolPerMin, …)`.
- Produces: `public OptionalDouble CgmFreshnessService.freshTrendMmolPerMin(UUID userId, Instant now)`.

- [ ] **Step 1: Pin the lag behaviour that is already in the tree**

Neither test exists yet. Both pass on the tree; they pin the review's numbers.

Create `InterstitialLagModelTest`. Save `InterstitialLagModel.tauMinutes` in `@BeforeEach`, set it to `PHYSIOLOGICAL_TAU_MIN`, and restore it in `@AfterEach`.

```java
@Test
void plasmaFromSensor_sevenMinuteLag_clampedTrend() {
    assertThat(InterstitialLagModel.PHYSIOLOGICAL_TAU_MIN).isEqualTo(7.0);
    assertThat(InterstitialLagModel.plasmaFromSensor(6.0, 0.1)).isCloseTo(6.7, within(1e-12));
    assertThat(InterstitialLagModel.plasmaFromSensor(6.0, 0.0)).isEqualTo(6.0);
    assertThat(InterstitialLagModel.plasmaFromSensor(6.0, -0.1)).isCloseTo(5.3, within(1e-12));
    assertThat(InterstitialLagModel.plasmaFromSensor(6.0, 1.0)).isCloseTo(6.0 + 7.0 * 0.15, within(1e-12));
    assertThat(InterstitialLagModel.plasmaFromSensor(6.0, Double.NaN)).isEqualTo(6.0);
}
```

Add to `HovorkaGlucosePredictionServiceTest`:

```java
@Test
@DisplayName("a rising CGM trend lifts the 15-minute forecast; the first point still leaves from the reading")
void risingTrendLiftsTheShortForecast() {
    List<PredictionPointDTO> flat = forecastWithTrend(0.0);
    List<PredictionPointDTO> rising = forecastWithTrend(0.1);
    assertThat(glucoseAt(rising, 15)).isGreaterThan(glucoseAt(flat, 15) + 0.4);
    assertThat(glucoseAt(rising, 5)).isLessThan(6.7);
}

private List<PredictionPointDTO> forecastWithTrend(double trend) {
    return service.buildPredictionPath(
            params, new RapidInsulinIobParameters(4.5, 55.0), null, 6.0, NOW,
            List.of(), List.of(), List.of(), USER_ID, 30,
            ActivityProvider.NONE, BasalInsulinResolver.DEFAULT_DURATION_HOURS,
            ActivityGains.NEUTRAL, 1.0, trend);
}
```

Run: `./gradlew test --tests che.glucosemonitorbe.hovorka.InterstitialLagModelTest --tests che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionServiceTest`

Expected: PASS. If `risingTrendLiftsTheShortForecast` fails, stop and report: the tree's lag wiring is not what this plan assumes.

- [ ] **Step 2: Write the failing slope test**

In `CgmFreshnessService`, add an empty version first, so the red is a value and not a compile error that stops every test:

```java
public OptionalDouble freshTrendMmolPerMin(UUID userId, Instant now) {
    return OptionalDouble.empty();
}
```

Add to `CgmFreshnessServiceTest` (imports: `java.util.List`, AssertJ `within`):

```java
@Test
@DisplayName("the trend is the slope from the reading about 15 minutes before the newest one")
void trendFromTheReadingFifteenMinutesEarlier() {
    long newest = NOW.minus(Duration.ofMinutes(2)).toEpochMilli();
    long earlier = newest - Duration.ofMinutes(14).toMillis();
    when(readings.findFirstByUserIdOrderByDateTimestampDesc(userId)).thenReturn(Optional.of(
            CgmReading.builder().userId(userId).sgv(151).dateTimestamp(newest).build()));
    long target = newest - Duration.ofMinutes(15).toMillis();
    when(readings.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
            userId, target - 300_000L, target + 300_000L))
            .thenReturn(List.of(
                    CgmReading.builder().userId(userId).sgv(90).dateTimestamp(target - 280_000L).build(),
                    CgmReading.builder().userId(userId).sgv(126).dateTimestamp(earlier).build()));

    // 151 - 126 = 25 mg/dL over 14 min, from the reading nearest the 15-minute mark
    assertThat(service.freshTrendMmolPerMin(userId, NOW).getAsDouble())
            .isCloseTo(25.0 / 18.0 / 14.0, within(1e-9));
}

@Test
@DisplayName("no trend without a fresh reading, or without an earlier one")
void noTrendWithoutAFreshReadingOrAnEarlierOne() {
    newestReadingAgeMinutes(16);
    assertThat(service.freshTrendMmolPerMin(userId, NOW)).isEmpty();

    newestReadingAgeMinutes(2);
    assertThat(service.freshTrendMmolPerMin(userId, NOW)).isEmpty();

    when(libre.upstreamStatus(userId)).thenReturn(new LibreUpstreamStatusDTO(false, "OPEN", 120L));
    assertThat(service.freshTrendMmolPerMin(userId, NOW)).isEmpty();
}
```

Run: `./gradlew test --tests che.glucosemonitorbe.assistant.common.CgmFreshnessServiceTest`

Expected: `trendFromTheReadingFifteenMinutesEarlier` fails with `NoSuchElementException` (the stub is empty). The second test passes against the stub; it is a guard.

- [ ] **Step 3: Implement `freshTrendMmolPerMin`**

Rule: the newest reading must be fresh by `freshReading`'s own definition (at most 15 minutes old, upstream available). Take the stored reading nearest to 15 minutes before it, within ±5 minutes, ignoring rows with a null `sgv` or timestamp. The trend is the glucose difference in mmol/L divided by the actual minutes between the two readings. Empty when either reading is missing. No clamp here; `plasmaFromSensor` clamps.

```java
static final Duration TREND_WINDOW = Duration.ofMinutes(15);
static final Duration TREND_TOLERANCE = Duration.ofMinutes(5);
```

Read the newest row from the repository, as `freshReading` does, and compute the slope from its raw `dateTimestamp` and `sgv` in epoch milliseconds, never through a `LocalDateTime`.

Run: `./gradlew test --tests che.glucosemonitorbe.assistant.common.CgmFreshnessServiceTest`

Expected: PASS.

- [ ] **Step 4: Write the failing wiring test**

Add to `DashboardMealMacrosWiringTest`:

```java
@Test
@DisplayName("a request without a CGM trend starts from the stored one; a request trend wins")
void storedTrendFillsAMissingRequestTrend() throws Exception {
    when(noteRepository.findByUserIdAndTimestampBetween(any(UUID.class), any(), any())).thenReturn(List.of());
    assertThat(trendPassedFor(null)).as("no stored-trend source wired").isEqualTo(0.0);

    Field f = GlucoseCalculationsService.class.getDeclaredField("cgmFreshnessService");
    f.setAccessible(true);
    f.set(service, cgmFreshnessService);
    when(cgmFreshnessService.freshTrendMmolPerMin(eq(userId), any(Instant.class)))
            .thenReturn(OptionalDouble.of(0.08));
    assertThat(trendPassedFor(null)).isEqualTo(0.08);
    assertThat(trendPassedFor(0.02)).isEqualTo(0.02);

    when(cgmFreshnessService.freshTrendMmolPerMin(eq(userId), any(Instant.class)))
            .thenReturn(OptionalDouble.empty());
    assertThat(trendPassedFor(null)).as("no fresh reading").isEqualTo(0.0);

    when(cgmFreshnessService.freshTrendMmolPerMin(eq(userId), any(Instant.class)))
            .thenThrow(new IllegalStateException("db down"));
    assertThat(trendPassedFor(null)).as("a failed lookup never fails the forecast").isEqualTo(0.0);
}

private double trendPassedFor(Double requestTrend) {
    clearInvocations(hovorkaService);
    service.calculateGlucoseData(GlucoseCalculationsRequest.builder()
            .currentGlucose(8.0).userId("u").currentTrendMmolPerMin(requestTrend).build());
    ArgumentCaptor<Double> trend = ArgumentCaptor.forClass(Double.class);
    verify(hovorkaService).buildPredictionPath(anyDouble(), trend.capture(), any(LocalDateTime.class), anyList(),
            anyList(), anyList(), any(UUID.class), anyInt(), any(ActivityProvider.class));
    return trend.getValue();
}
```

Add `@Mock private CgmFreshnessService cgmFreshnessService;` to the class, and imports for `CgmFreshnessService`, `java.time.Instant` and `java.util.OptionalDouble`.

Run: `./gradlew test --tests che.glucosemonitorbe.service.DashboardMealMacrosWiringTest`

Expected: `storedTrendFillsAMissingRequestTrend` fails with `NoSuchFieldException: cgmFreshnessService`, after its first assertion has passed.

- [ ] **Step 5: Use the stored trend in the Hovorka path**

In `GlucoseCalculationsService`, next to the other optional fields (line 96):

```java
/** Lazy and optional: the stored CGM trend, for requests that carry none (web, alerts, chat). */
@Lazy
@Autowired(required = false)
private CgmFreshnessService cgmFreshnessService;
```

In the Hovorka branch of the private `buildPredictionPath` (line 426), pass `currentTrendMmolPerMin != null ? currentTrendMmolPerMin : storedTrend(userUUID)`. Add `private double storedTrend(UUID userId)`: 0.0 when the field is null; otherwise `freshTrendMmolPerMin(userId, Instant.now()).orElse(0.0)`; a `RuntimeException` is logged at warn and returns 0.0.

Leave the OpenAPS branch below it (line 439) and `calculateDashboard` alone: the momentum term and the carb advice keep reading the request's own trend.

Two things this relies on, both checked by reading the callers and neither by a test:

- Every caller that sends no trend forecasts at the present moment from the current reading. A caller that forecasts from a past time or a hypothetical glucose must send its own trend (0 if it has none), or it will get today's.
- Each such forecast now costs two indexed CGM lookups and one upstream-status read. `PreBolusPauseOptimizer` runs many forecasts per request; if Step 7 shows a slowdown there, have it resolve the trend once and send it, and report that.

Run: `./gradlew test --tests che.glucosemonitorbe.service.DashboardMealMacrosWiringTest`

Expected: PASS.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests che.glucosemonitorbe.assistant.common.CgmFreshnessServiceTest --tests che.glucosemonitorbe.service.DashboardMealMacrosWiringTest --tests che.glucosemonitorbe.service.GlucoseCalculationsServiceTest`

Expected: PASS.

- [ ] **Step 7: Run the whole suite**

Run: `./gradlew test`

Expected: PASS. Integration tests that seed a fresh CGM reading with earlier readings now forecast from a trend. If one of them pins a forecast value that moved for that reason, re-pin it and say so in the hand-off; anything else, stop and report. If a test cannot start for an environment reason (no database, no Docker), name it in the hand-off and do not treat it as a pass.

---

## After the last task

Report to the user: the Task 1 failure list as actually observed, both CGMacros tables, and every re-pinned literal with its cause. Stored digital twins were fitted on the old gut rates; they refit on the next nightly calibration.

## Out of scope

- **Glycemic event detector and tail service.** They anchor on a CGM series with trend 0. Passing a trend would shift a four-hour residual scan by up to 1 mmol/L for its whole window, against thresholds tuned without it, and the offline scorecards (`GlycemicEventReplay`) would need the same change before the effect could be measured. Worth its own change with a before/after scorecard.
- **A two-sided brake** that speeds up meals leaner than the reference. See Task 3.
- **The iPhone's trend.** It sends one of five arrow velocities (±0.033, ±0.067, ±0.100 mmol/L per minute), which is coarser than the stored series. Not changed.
- **Finding 4 comments.** `DigitalTwinCalibrator` says the clamp band is 0.4–1.6; `TwinScales` clamps 0.5–2.0. Not part of 5–8.
- **The review's "Smaller points"** (exercise tail, fat's late rise, fiber in COB, residual bias).
