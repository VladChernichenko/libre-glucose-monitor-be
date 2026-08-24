# Time-dependent ISF Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every dose figure the backend computes — recommended, or merely displayed as guidance — use the insulin-sensitivity factor in force at that figure's own time of day, instead of a single base value.

> **Corrected after execution.** This plan was written believing the app recommends doses to the user. It does not: `/api/insulin/calculate` has no callers, the web frontend's `calculateRecommendedInsulin` had none either and has since been deleted, and iOS has no bolus calculator. See the spec's *Reachability* section. The one genuinely live path — the AI correction-guidance card — was missed by this plan entirely and was fixed in the post-review wave (Task 4 below). Task-level claims about what "the app would have recommended" are false and are marked where they appear.

**Architecture:** Two independent changes. `InsulinCalculatorService` swaps `settings.getIsf()` for the existing `settings.getEffectiveIsf(t)`, resolved on the client's clock. Separately, the ODE's x3 bridge stops dividing a per-dose-ISF sum by a single ISF — the caller passes the summed insulin activity rate directly, so the ISF cancels out of the solver.

**Tech Stack:** Java 21, Spring Boot 3.5.5, JUnit 5 + Mockito + AssertJ.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-22-time-dependent-isf-design.md`
- Branch: `feat/time-dependent-isf` (already created; the spec is committed on it)
- **No schema change.** `user_settings.isf` is retained as the fallback. Do not edit any file under `src/main/resources/db/migration/`.
- `getEffectiveIsf(t)` returns the window override if set, else the base `isf`, else `null`. A `null` ISF must continue to refuse with `DosingRefusalReason.SETTINGS_INVALID` — never substitute a value in a dosing path.
- Meal windows: BREAKFAST 05:00–10:59, LUNCH 11:00–15:59, DINNER 16:00–21:59, NIGHT 22:00–04:59.
- `gramsPerUnit = 10.0 × isf / carbRatio`; the 3–30 g/U envelope is a refusal boundary, not a clamp. Unchanged.
- **Bit-identity bar (Task 2):** any forecast whose active doses all share one ISF must emit a byte-identical curve. This includes forecasts with activity notes.
  *(Achieved, but narrower than stated: shim callers are exact by construction; the production path is empirical plus a bounded error of roughly 5e-3 mmol/L, because `p.isf()` is `settings.isf × isfScale` for digital-twin users and so never cancelled exactly. Well under the 0.1 mmol/L emission quantum.)*
- Backend suite must end at **1129 tests, 0 failures**, and `./gradlew jacocoTestCoverageVerification` must pass. Both are green at the branch point. *(Shipped at 1142 / 0.)*
- Run tests with `./gradlew test --tests '<pattern>'`. Do not run the full suite except where a step says to.
- Read a file before editing it. Never save working files to the repo root; never commit scratch files. Do not push.

---

### Task 1: Dosing resolves ISF by meal window

This is the defect in the dose calculator. ~~On 21 Aug the app would have recommended 16 u for an 80 g dinner where the user's own `isf_dinner` implies 10.7 u — into a meal that ended at 3.8 mmol/L.~~

**Correction:** no surface would have shown either figure — this endpoint has no clients. 16 u versus 10.7 u is arithmetic over the user's stored settings, not a number the app can display. The task is still worth doing: a plausible-looking dose function that is quietly wrong is a trap for whoever wires up a dose surface later, and the error always over-doses whenever a window override exceeds the base.

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java` (`resolveGramsPerUnit` ~line 256, `calculateRecommendedInsulin` ~line 281)
- Test: `src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceIsfWindowTest.java` (new)

**Interfaces:**
- Consumes: `UserSettingsDTO.getEffectiveIsf(LocalDateTime)` → `Double` (exists); `InsulinCalculationRequest.getClientTimeInfo()` → `ClientTimeInfo` (exists); `ClientTimeInfo.toLocalDateTime()` → `LocalDateTime`, which returns `LocalDateTime.now()` when its `timestamp` is null or unparseable (exists).
- Produces: `InsulinCalculatorService.resolveGramsPerUnit(UserSettingsDTO, LocalDateTime)` — signature gains the time argument.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceIsfWindowTest.java`:

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.dto.ClientTimeInfo;
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.dto.UserSettingsDTO;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Dosing must price a bolus with the ISF in force at that bolus's own time of day.
 *
 * <p>These are the real stored settings of the 21 Aug incident: carbRatio 2.0, base isf 1.0,
 * isf_dinner 1.5, isf_night 1.0. An 80 g dinner priced on the base ISF yields 16 u; priced on
 * the dinner ISF it yields 10.7 u. The user took 10 u and still finished the evening at
 * 3.8 mmol/L.
 */
class InsulinCalculatorServiceIsfWindowTest {

    private static final UUID USER_ID = UUID.randomUUID();

    private UserSettingsService userSettingsService;
    private InsulinCalculatorService service;

    @BeforeEach
    void setUp() {
        userSettingsService = mock(UserSettingsService.class);
        UserSettingsDTO settings = new UserSettingsDTO(
                UUID.randomUUID(), USER_ID, /*carbRatio*/ 2.0, /*isf*/ 1.0,
                /*halfLife*/ 45, /*maxCob*/ 240);
        settings.setIsfBreakfast(1.5);
        settings.setIsfLunch(1.5);
        settings.setIsfDinner(1.5);
        settings.setIsfNight(1.0);
        settings.setBodyWeightKg(84.0);
        when(userSettingsService.getUserSettings(any(UUID.class))).thenReturn(settings);
        service = new InsulinCalculatorService(userSettingsService);
    }

    /** 18:00 is DINNER: gramsPerUnit = 10 x 1.5 / 2.0 = 7.5, so 80 g -> 10.67 u. */
    @Test
    void mealDoseUsesTheDinnerIsfForADinnerTimeRequest() {
        InsulinCalculationResponse r = service.calculateRecommendedInsulin(
                request(80.0, 6.5, 6.5, "2026-08-21T18:00:00"));
        assertThat(r.getRecommendedInsulin()).isCloseTo(10.67, within(0.01));
    }

    /**
     * 23:00 is NIGHT, where the override equals the base: gramsPerUnit = 5.0, so 80 g -> 16 u.
     * Without this the dinner assertion alone would also pass an implementation that had merely
     * been rebased onto a different single constant.
     */
    @Test
    void mealDoseUsesTheNightIsfForALateRequest() {
        InsulinCalculationResponse r = service.calculateRecommendedInsulin(
                request(80.0, 6.5, 6.5, "2026-08-21T23:00:00"));
        assertThat(r.getRecommendedInsulin()).isCloseTo(16.0, within(0.01));
    }

    /** Correction at the 20:07 glucose of the incident: (14.1 - 6.5) / 1.5 = 5.07 u. */
    @Test
    void correctionDoseUsesTheDinnerIsf() {
        InsulinCalculationResponse r = service.calculateRecommendedInsulin(
                request(0.0, 14.1, 6.5, "2026-08-21T20:07:00"));
        assertThat(r.getRecommendedInsulin()).isCloseTo(5.07, within(0.01));
    }

    /** Same correction at night, where ISF is 1.0: (14.1 - 6.5) / 1.0 = 7.6 u. */
    @Test
    void correctionDoseUsesTheNightIsf() {
        InsulinCalculationResponse r = service.calculateRecommendedInsulin(
                request(0.0, 14.1, 6.5, "2026-08-21T23:30:00"));
        assertThat(r.getRecommendedInsulin()).isCloseTo(7.6, within(0.01));
    }

    /** No window override and no base ISF: refuse, never guess. */
    @Test
    void refusesWhenNeitherWindowNorBaseIsfIsSet() {
        UserSettingsDTO bare = new UserSettingsDTO(
                UUID.randomUUID(), USER_ID, /*carbRatio*/ 2.0, /*isf*/ null,
                /*halfLife*/ 45, /*maxCob*/ 240);
        when(userSettingsService.getUserSettings(any(UUID.class))).thenReturn(bare);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(
                request(80.0, 6.5, 6.5, "2026-08-21T18:00:00")))
                .isInstanceOf(DosingRefusedException.class)
                .hasFieldOrPropertyWithValue("reason", DosingRefusalReason.SETTINGS_INVALID);
    }

    /** A missing clientTimeInfo must not throw - it falls back to the server clock. */
    @Test
    void missingClientTimeFallsBackToNowWithoutThrowing() {
        InsulinCalculationRequest req = request(80.0, 6.5, 6.5, null);
        req.setClientTimeInfo(null);
        assertThat(service.calculateRecommendedInsulin(req).getRecommendedInsulin())
                .isGreaterThan(0.0);
    }

    private InsulinCalculationRequest request(double carbs, double current, double target,
                                              String isoTimestamp) {
        InsulinCalculationRequest req = new InsulinCalculationRequest();
        req.setUserId(USER_ID.toString());
        req.setCarbs(carbs);
        req.setCurrentGlucose(current);
        req.setTargetGlucose(target);
        req.setActiveInsulin(0.0);
        if (isoTimestamp != null) {
            ClientTimeInfo cti = new ClientTimeInfo();
            cti.setTimestamp(isoTimestamp);
            req.setClientTimeInfo(cti);
        }
        return req;
    }
}
```

`DosingRefusedException.getReason()` returns a `DosingRefusalReason`, so `hasFieldOrPropertyWithValue("reason", ...)` resolves correctly.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.InsulinCalculatorServiceIsfWindowTest'`
Expected: FAIL. `mealDoseUsesTheDinnerIsfForADinnerTimeRequest` returns 16.0 instead of 10.67, and `correctionDoseUsesTheDinnerIsf` returns 7.6 instead of 5.07 — both because the base ISF is used. The two NIGHT tests will already pass, since the night override equals the base.

- [ ] **Step 3: Thread the time into `resolveGramsPerUnit`**

In `InsulinCalculatorService`, change the signature and the ISF source:

```java
    private double resolveGramsPerUnit(UserSettingsDTO settings, LocalDateTime at) {
        Double isf = settings.getEffectiveIsf(at);
        Double carbRatio = settings.getCarbRatio();
        if (isf == null || carbRatio == null
                || !Double.isFinite(isf) || !Double.isFinite(carbRatio)
                || isf <= 0 || carbRatio <= 0) {
            throw new DosingRefusedException(DosingRefusalReason.SETTINGS_INVALID,
                    "isf=" + isf + " carbRatio=" + carbRatio + " at=" + at);
        }
        double gramsPerUnit = 10.0 * isf / carbRatio;
        if (gramsPerUnit < MIN_GRAMS_PER_UNIT || gramsPerUnit > MAX_GRAMS_PER_UNIT) {
            throw new DosingRefusedException(DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT,
                    "derived gramsPerUnit=" + gramsPerUnit + " from isf=" + isf
                            + " carbRatio=" + carbRatio + " at=" + at);
        }
        return gramsPerUnit;
    }
```

Add the import for `java.time.LocalDateTime` if it is not already present.

- [ ] **Step 4: Resolve the dose time and use it for both ISF reads**

In `calculateRecommendedInsulin`, replace the first three lines of the method body:

```java
    public InsulinCalculationResponse calculateRecommendedInsulin(InsulinCalculationRequest request) {
        UserSettingsDTO settings = requireSettings(request.getUserId());

        // Meal windows are wall-clock boundaries, so the client's clock decides which one a dose
        // falls in. A server in another zone would price an 18:00 dinner bolus as lunch. Mirrors
        // how GlucoseCalculationsService already treats ClientTimeInfo as authoritative.
        LocalDateTime doseTime = request.getClientTimeInfo() != null
                ? request.getClientTimeInfo().toLocalDateTime()
                : LocalDateTime.now();

        double gramsPerUnit = resolveGramsPerUnit(settings, doseTime);
        double isf = settings.getEffectiveIsf(doseTime);
```

Leave the rest of the method unchanged. `resolveGramsPerUnit` has already refused on a null ISF by the time that last line runs, so the unboxing is safe.

- [ ] **Step 5: Fix the other call site of `resolveGramsPerUnit`**

Run `grep -n "resolveGramsPerUnit" src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java`. If any caller other than `calculateRecommendedInsulin` exists, pass it a `LocalDateTime` resolved the same way — from that caller's own request time if it has one, otherwise `LocalDateTime.now()`. Do not add an overload that defaults the time silently; every caller must state which clock it means.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.InsulinCalculatorServiceIsfWindowTest'`
Expected: PASS, 6 tests.

- [ ] **Step 7: Confirm the existing dosing tests still pass**

Run: `./gradlew test --tests 'che.glucosemonitorbe.service.InsulinCalculatorServiceTest' --tests 'che.glucosemonitorbe.controller.InsulinCalculatorControllerTest'`
Expected: PASS.

Those fixtures set no window overrides, so `getEffectiveIsf` returns the base and every existing expectation holds. If one fails, that is a real behaviour change — report it rather than adjusting the expectation.

- [ ] **Step 8: Prove the test discriminates**

Temporarily revert `resolveGramsPerUnit`'s ISF read to `settings.getIsf()`, re-run `InsulinCalculatorServiceIsfWindowTest`, and confirm `mealDoseUsesTheDinnerIsfForADinnerTimeRequest` fails. Restore, confirm green, and confirm `git diff` on the service is clean of the temporary edit. Report both outcomes.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java \
        src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceIsfWindowTest.java
git commit -m "fix(dosing): price a bolus with the ISF in force at its own time

InsulinCalculatorService read the base user_settings.isf for both the meal
divisor and the correction, ignoring the per-meal-window overrides the
Settings screen presents as live. For the 21 Aug case that is 16u recommended
for an 80g dinner where the user's own isf_dinner implies 10.7u.

The window is resolved on the client's clock, since meal windows are
wall-clock boundaries. A null ISF still refuses rather than guessing."
```

---

### Task 2: Remove the ISF scalar from the ODE's x3 bridge

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaOdeSolver.java` (`step` overloads ~lines 140-190, `derivatives` ~line 247, x3 bridge ~line 300)
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java` (per-minute loop ~lines 368-410)
- Test: `src/test/java/che/glucosemonitorbe/hovorka/HovorkaInsulinActivityBridgeTest.java` (new)

**Interfaces:**
- Consumes: `HovorkaOdeSolver.V_I_SCALE` (static, 12.0); `HovorkaParameters.effectiveInsulinVolume()` → `double`.
- Produces: an 8-argument `step(HovorkaState, HovorkaParameters, double carbMmolNow, int mealGI, double protFatKcalNow, double insulinEffect, double insulinActivityRate, double activityRate)` and a matching 7-argument `derivatives(double[], HovorkaParameters, double mealMmol, int gi, double insulinEffect, double insulinActivityRate, double activityUptakeRate)`.

- [ ] **Step 1: Understand what cancels, before changing anything**

`HovorkaGlucosePredictionService:374` builds the effect as a per-dose sum:

```java
insulinEffect += dose.isf() * pAdj.effectiveInsulinVolume() * iobActivityRate;
```

`HovorkaOdeSolver:300` then inverts it with a single ISF:

```java
double plasmInsulin = (p.isf() * p.effectiveInsulinVolume() > 0)
        ? insulinEffect / (p.isf() * p.effectiveInsulinVolume()) * V_I_SCALE
        : 0.0;
```

When every dose shares one ISF, `Σ(isf × V × rate) / (isf × V) = Σ(rate)` exactly, so the ISF cancels and the result is `Σ(rate) × V_I_SCALE`. When they differ it is an ISF-weighted blend with no physiological meaning. Passing `Σ(rate)` directly is the same number in the first case and the correct one in the second.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/hovorka/HovorkaInsulinActivityBridgeTest.java`:

```java
package che.glucosemonitorbe.hovorka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The x3 bridge approximates plasma insulin from insulin activity. It used to divide the
 * insulin effect by a single ISF, but the effect is a sum over doses each carrying the ISF of
 * the window it was given in - so one ISF was dividing a multi-ISF sum. The caller now passes
 * the summed activity rate directly and the ISF cancels out of the solver.
 */
class HovorkaInsulinActivityBridgeTest {

    private final HovorkaOdeSolver solver = new HovorkaOdeSolver();

    private static HovorkaParameters params(double isf) {
        double weight = 84.0;
        return new HovorkaParameters(
                HovorkaParameters.VG_PER_KG * weight,
                HovorkaParameters.F01_PER_KG * weight,
                HovorkaParameters.F01_PER_KG * weight,
                HovorkaParameters.EGP0_PER_KG * weight,
                HovorkaParameters.K12_POP, HovorkaParameters.K21_POP,
                45.0 / HovorkaParameterService.HALF_LIFE_TO_TMAX_G,
                1.0, isf, weight);
    }

    /**
     * Single-ISF equivalence: feeding the explicit rate must reproduce exactly what the old
     * division produced, so no existing forecast moves.
     */
    @Test
    void explicitRateReproducesTheOldDivisionWhenAllDosesShareOneIsf() {
        HovorkaParameters p = params(1.5);
        double rate = 0.004;                                     // units/min
        double effect = 1.5 * p.effectiveInsulinVolume() * rate;  // one dose at isf 1.5

        HovorkaState viaRate = solver.step(
                HovorkaState.steadyState(7.0, p), p, 0.0, 70, 0.0, effect, rate, 0.0);

        // The pre-change equivalent: effect / (isf x V), which for one dose IS rate.
        double impliedRate = effect / (p.isf() * p.effectiveInsulinVolume());
        HovorkaState viaDivision = solver.step(
                HovorkaState.steadyState(7.0, p), p, 0.0, 70, 0.0, effect, impliedRate, 0.0);

        assertThat(viaRate.x3()).isEqualTo(viaDivision.x3());
        assertThat(viaRate.q1()).isEqualTo(viaDivision.q1());
    }

    /**
     * Two doses at different window ISFs. The correct plasma-insulin driver is the sum of the
     * activity rates, independent of either ISF - the old form would have produced an
     * ISF-weighted blend instead.
     */
    @Test
    void plasmaInsulinTracksTotalActivityRateNotTheIsfBlend() {
        HovorkaParameters p = params(1.0);
        double v = p.effectiveInsulinVolume();
        double rateA = 0.003, rateB = 0.002;
        double effect = 1.5 * v * rateA + 1.0 * v * rateB;   // dinner dose + night dose
        double totalRate = rateA + rateB;

        HovorkaState after = solver.step(
                HovorkaState.steadyState(7.0, p), p, 0.0, 70, 0.0, effect, totalRate, 0.0);

        // dx3 = -KA3*x3 + KB3*plasmInsulin, starting from x3 = 0 over one RK4 minute.
        // With x3(0)=0 the leading term is KB3 * totalRate * V_I_SCALE.
        double expectedLeading = HovorkaOdeSolver.KB3 * totalRate * HovorkaOdeSolver.V_I_SCALE;
        assertThat(after.x3()).isCloseTo(expectedLeading, within(expectedLeading * 0.05));
    }
}
```

`HovorkaState.steadyState(double glucoseMmolL, HovorkaParameters p)` is the factory the existing solver tests use (see `HovorkaOdeSolverTest:59`). `KB3` and `V_I_SCALE` are package-private statics on `HovorkaOdeSolver`, and this test sits in the same package, so both are directly accessible.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests 'che.glucosemonitorbe.hovorka.HovorkaInsulinActivityBridgeTest'`
Expected: FAIL — compilation error, no 8-argument `step` overload.

- [ ] **Step 4: Add the rate parameter to the solver**

In `HovorkaOdeSolver`, add `double insulinActivityRate` to the master `step` overload between `insulinEffect` and `activityRate`, and thread it into all four `derivatives` calls:

```java
        double[] k1 = derivatives(y, p, mealMmol, gi, insulinEffect, insulinActivityRate, activityRate);
        double[] k2 = derivatives(add(y, scale(k1, 0.5)), p, mealMmol, gi, insulinEffect, insulinActivityRate, activityRate);
        double[] k3 = derivatives(add(y, scale(k2, 0.5)), p, mealMmol, gi, insulinEffect, insulinActivityRate, activityRate);
        double[] k4 = derivatives(add(y, k3),             p, mealMmol, gi, insulinEffect, insulinActivityRate, activityRate);
```

Add the same parameter to `derivatives` in the same position, and replace the x3 bridge:

```java
        // Plasma insulin I(t) is driven by how fast insulin is acting, not by how much glucose
        // that action removes. The caller supplies the summed IOB activity rate directly: the
        // effect is Sum(dose.isf x V x rate), so dividing it by any single ISF was exact only
        // while every dose shared one - which per-meal-window ISF no longer guarantees.
        double plasmInsulin = insulinActivityRate * V_I_SCALE;
```

- [ ] **Step 5: Keep the shorter overloads behaviour-identical**

The 4-arg and 5-arg `step` overloads are used by roughly twenty existing test call sites that pass an aggregate effect with no per-dose decomposition. For those callers, `effect / (isf × V)` **is** the correct inverse, so they keep it — as an explicit shim, not an oversight:

```java
    /**
     * Convenience overload for callers that supply a single aggregate insulin effect with no
     * per-dose breakdown. The activity rate is recovered by inverting the effect through the
     * parameter ISF, which is exact for a single-ISF caller. The production path uses the
     * explicit-rate overload instead.
     */
    private static double impliedActivityRate(HovorkaParameters p, double insulinEffect) {
        double denom = p.isf() * p.effectiveInsulinVolume();
        return denom > 0 ? insulinEffect / denom : 0.0;
    }
```

Have both shorter overloads delegate with `impliedActivityRate(p, insulinEffect)`. This is a deliberate deviation from the spec's "the solver no longer divides by it": the division survives on the aggregate-effect convenience path, where it is mathematically correct, and is gone from the production path. Flag it in your report so the reviewer sees it rather than discovers it.

- [ ] **Step 6: Sum the activity rate in the prediction service**

In `HovorkaGlucosePredictionService`, inside the per-minute loop, accumulate the rate alongside the effect:

```java
            double insulinEffect = 0.0;
            double insulinActivityRate = 0.0;
            for (DoseActivity dose : doseActivities) {
                double iobActivityRate = iobActivityRate(dose.iobTimeline(), min - 1);
                insulinEffect       += dose.isf() * pAdj.effectiveInsulinVolume() * iobActivityRate;
                insulinActivityRate += iobActivityRate;
            }
```

Then pass it at both `step` call sites:

```java
            if (hasActivity) {
                double aInst = activityProvider.intensityAt(currentTime.plusMinutes(min));
                double aSens = activity.stepSensitivity(aInst);
                double sensFactor = activity.insulinSensitivityFactor(aSens);
                double insulinEffectMod = insulinEffect * sensFactor;
                // Scaled identically to the effect so this change stays purely algebraic. Whether
                // exercise should amplify plasma insulin (it should not - it amplifies insulin
                // ACTION) is a real physiological question, deliberately left alone here.
                double insulinActivityRateMod = insulinActivityRate * sensFactor;
                state = odeSolver.step(state, pStep, carbMmol, mealGI, protFatKcalNow,
                        insulinEffectMod, insulinActivityRateMod, activity.uptakeRate(aInst));
            } else {
                state = odeSolver.step(state, pStep, carbMmol, mealGI, protFatKcalNow,
                        insulinEffect, insulinActivityRate, 0.0);
            }
```

- [ ] **Step 7: Run the new test and the whole hovorka package**

Run: `./gradlew test --tests 'che.glucosemonitorbe.hovorka.*'`
Expected: PASS, including all pre-existing Hovorka tests unmodified.

If any pre-existing Hovorka test fails, **stop and report**. Those tests encode physiological behaviour that has been debugged repeatedly; do not adjust one to accommodate this change without flagging it.

- [ ] **Step 8: Commit the golden-curve bit-identity fixture**

The bar is that a forecast whose doses share one ISF emits a byte-identical curve, **including with an activity note** — the 21 Aug case had a walking note, so the activity branch is not a corner case here.

*(As executed: exact for shim callers, empirical plus ~5e-3 mmol/L for the production path — see Global Constraints. Also note the ±1 % `V_I_SCALE` discrimination check specified below is **inert**: it moves 0 of 108 emitted points, because the x3 bridge sits ~30–50× under the emission quantum. The implementer substituted a pinned `x3` literal that does fail at +1 %.)*

Add to `HovorkaInsulinActivityBridgeTest` a test that builds one representative forecast through `HovorkaGlucosePredictionService.buildPredictionPath` — a single bolus, no window overrides, one activity note — and asserts the emitted `predictedGlucose` series against expected values pinned as literals in the test.

Generate those literals from the current code and **say so explicitly in your report**: that is legitimate for a characterization test, but it must be stated, not implied. Follow the fixture-construction style of the existing tests in `src/test/java/che/glucosemonitorbe/hovorka/`.

Verify it discriminates: perturb `V_I_SCALE` by 1%, confirm the golden test fails, restore it, confirm green. Report the outcome.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/hovorka/HovorkaOdeSolver.java \
        src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java \
        src/test/java/che/glucosemonitorbe/hovorka/HovorkaInsulinActivityBridgeTest.java
git commit -m "fix(prediction): drive the x3 bridge from insulin activity, not an ISF quotient

The bridge recovered plasma insulin by dividing the insulin effect by a single
ISF, but the effect is Sum(dose.isf x volume x rate) with each dose carrying
its own meal-window ISF. Exact while every dose shared one ISF; an
ISF-weighted blend once they differ.

The caller now passes the summed activity rate directly, so the ISF cancels
out of the solver. Single-ISF forecasts are byte-identical, pinned by a
committed golden-curve fixture including the activity branch."
```

---

### Task 3: Full verification

**Files:** none modified unless a gate fails.

- [ ] **Step 1: Run the whole backend suite**

Run: `./gradlew test`
Expected: **1129 tests + the tests added by Tasks 1 and 2, 0 failures.** *(Actual: 1142 / 0.)*

Note the branch point is genuinely green — the three DNS-dependent Nightscout failures were fixed in `3daeb72`. Any failure here is yours.

- [ ] **Step 2: Run the coverage gate**

Run: `./gradlew jacocoTestCoverageVerification`
Expected: PASS. `InsulinCalculatorService` is inside the 80 % line-coverage rule and gained a branch in Task 1.

If it fails on `InsulinCalculatorService`, add the missing case to `InsulinCalculatorServiceIsfWindowTest` rather than lowering the threshold, and say which branch was uncovered.

- [ ] **Step 3: Verify the build**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit only if a gate required a new test**

```bash
git add src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceIsfWindowTest.java
git commit -m "test(dosing): cover the remaining ISF-window branches"
```

---

### Task 4: The AI correction-guidance card — added after the whole-branch review

**This task was not in the plan as written.** The whole-branch review found it, and it is the only path on this branch that a user can actually reach. Recorded here so the plan matches what shipped.

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/ai/ContextAggregatorService.java` (the correction-estimate ISF read)
- Test: `ContextAggregatorService`'s test class — `buildContext_correctionUnitsTrackMealWindowIsf`

**The defect.** `ContextAggregatorService` computed `(latestGlucose − 6.5) / isf − activeIob` on the **base** ISF, and `SafetyAndScoringService` renders that to the patient at priority `high` as "Correction guidance estimate is ~Xu". Same formula and same 6.5 target as `InsulinCalculatorService`'s correction leg — so once Task 1 landed, the two disagreed for the same patient at the same moment: 5.1 u from the calculator, 7.6 u from the card.

Unlike the calculator, this card is reachable, via `POST /api/ai-insights/retrospective`.

**The fix.** Resolve with `getEffectiveIsf(end)`, where `end` is the instant the analysis window closes — the same "now" already used for `activeCob`, `activeIob` and `predicted2h`, and reported as the context's `windowEnd`. That matches `InsulinCalculatorService`, which resolves at the dose time rather than at the CGM reading's own timestamp, so the two converge on identical semantics rather than merely both being window-aware.

`SafetyAndScoringService` is deliberately untouched: it renders whatever the aggregator produces. Fixing the source rather than the renderer keeps one definition of the correction dose.

**Test.** Same glucose and IOB at two times of day in different windows must yield two different `estimatedCorrectionUnits`. Mutation-checked: reverting to `getIsf()` fails exactly that test and no other.

Shipped as commit `9b6ccd4`.

---

## What this plan got wrong

Worth recording, because two of the three were caught only by an implementer refusing to transcribe blindly:

1. **The premise.** The plan was written believing the app recommends doses. It does not — see the header note. The one live path was missed entirely and is now Task 4.
2. **Task 2 named the wrong overloads.** It said the 4-arg and 5-arg `step()` overloads carry ~20 call sites and should host the shim. It is the **7-arg** overload, ~23 sites, and following the instruction literally would not have compiled. The implementer relocated the shim to the 7-arg `step` and 6-arg `derivatives`, preserving the intent.
3. **Task 2's discrimination check was inert.** Perturbing `V_I_SCALE` by 1 % moves 0 of 108 emitted points (0 at ×2, 2 at ×10) because the x3 bridge is ~30–50× below the emission quantum. Had it been followed literally, the plan would have "proved" discrimination with a check that could never fail. A pinned `x3` literal was substituted.

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §1 Dosing reads effective ISF (both call sites) | Task 1, Steps 3–5 |
| §1 Client-clock resolution | Task 1, Step 4 |
| §1 Refusal path unchanged | Task 1, Step 1 (`refusesWhenNeitherWindowNorBaseIsfIsSet`), Step 3 |
| §1 3–30 g/U envelope unchanged | Task 1, Step 3 — retained verbatim |
| §2 Remove ISF from the x3 bridge | Task 2, Steps 4–6 |
| §2 Bit-identity, including activity | Task 2, Steps 6 and 8 — achieved with the bounded-error caveat noted above |
| §3 AI correction-guidance card | **Task 4** — absent from this plan as written; added after the whole-branch review |
| §4 Experiments left alone | No task — spec explicitly defers this to sub-project 3 |
| Testing section | Tasks 1–2 and 4; gates in Task 3 |
| No schema change | Global Constraints; no task touches `db/migration` |

**Placeholder scan:** Clean. The two fixture signatures the plan depends on were verified against the source rather than hedged: `HovorkaState.steadyState(double, HovorkaParameters)` (used by `HovorkaOdeSolverTest:59`) and `DosingRefusedException.getReason()`. `UserSettingsDTO` has the 6-arg `(id, userId, carbRatio, isf, carbHalfLife, maxCOBDuration)` constructor the tests use. The one thing not inlined is Task 2 Step 8's golden literals, which cannot be written in advance by construction — they are generated from the current implementation, and the step requires that to be stated openly in the report.

**Type consistency:** `resolveGramsPerUnit(UserSettingsDTO, LocalDateTime)` is used with that signature in Task 1 Steps 3, 4 and 5. The 8-argument `step` and 7-argument `derivatives` are declared in Task 2's Interfaces block and used consistently in Steps 4, 6 and the Step 2 test. `insulinActivityRate` is the parameter name throughout; `insulinActivityRateMod` is the activity-scaled local, matching the existing `insulinEffect`/`insulinEffectMod` pair.
