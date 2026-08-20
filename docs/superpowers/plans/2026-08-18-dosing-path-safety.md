# Dosing-Path Safety (S1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop a single accepted refinement from halving or doubling every meal bolus, and remove `carbRatio` from the last user-visible output it does not actually govern.

**Architecture:** `carb_ratio` is a dosing parameter (`gramsPerUnit = 10 × isf / carbRatio`) that finding F12 proved has **zero** influence on the forecast — 1.0 vs 4.0 is bit-identical. It is nonetheless auto-titrated by `VerificationService` against the error of a linear formula the user never sees, with a `clamp(1 + relError, 0.5, 2.0)` step. This plan does not redesign carb magnitude; it removes the acute risk by bounding the step, and repoints the dashboard trend label at the actual prediction path so `carbRatio` no longer shapes anything the user reads outside the dose calculator. Re-basing the learning loop onto `agScale` — the scale that *is* live in the model — is Plan 3, and depends on model-side work not started here.

**Tech Stack:** Java 21, Spring Boot, Gradle, JUnit 5 + Mockito + AssertJ, Flyway, PostgreSQL 16.

## Global Constraints

- **Never add `ALTER TABLE` in a new migration.** Edit the migration that owns the table — `verification_summary` is owned by `V6__verification_events.sql`. A new `V{n+1}__` file is only for a brand-new table. (`glucose-monitor-be/CLAUDE.md`)
- Editing a shipped migration changes its Flyway checksum; against a populated database this needs `flyway repair`. Call this out in the commit body.
- Never save working files or tests to the repo root.
- Run the full suite after every code change; verify the build before committing.
- Docker must be running or 21 Testcontainers suites abort at init. Known-good baseline at `0f05959`: **1002 tests, 3 failures**, all three `Nightscout URL host could not be resolved` (a live-DNS SSRF guard, finding F16, unrelated to this work).
- Every behaviour change is TDD: failing test first, watch it fail for the right reason, then the minimal fix.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java` | Dashboard response assembly | `determineTrend` reads the emitted path; call site moves below path construction |
| `src/main/java/che/glucosemonitorbe/service/VerificationService.java` | Verification events + titration | Bound the per-acceptance step; drop dead `suggestedIsf` |
| `src/main/java/che/glucosemonitorbe/entity/VerificationSummary.java` | Summary entity | Remove `suggestedIsf` |
| `src/main/java/che/glucosemonitorbe/dto/VerificationSummaryDTO.java` | Summary DTO | Remove `suggestedIsf` |
| `src/main/resources/db/migration/V6__verification_events.sql` | Owns `verification_summary` | Drop the `suggested_isf` column |
| `src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameters.java` | Parameter record | Fix javadoc listing `carbRatio` as a model input |
| `src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameterService.java` | Builds params | Fix `applyScales` javadoc claiming `egpScale` reaches the ODE |
| `src/main/java/che/glucosemonitorbe/entity/VerificationEvent.java` | Event entity | Fix javadoc omitting the `/10` |
| `src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsServiceTest.java` | Existing | Add trend tests |
| `src/test/java/che/glucosemonitorbe/service/VerificationServiceTest.java` | Existing | Add step-bound tests |

---

### Task 1: Trend label reads the prediction path

The dashboard chip showing "rising" / "falling" / "stable" is computed from
`carbContribution + insulinContribution + ...`, where `carbContribution = ((COB − futureCOB)/10) × carbRatio`.
Since `carbRatio` cannot move the curve, the chip and the curve beside it are computed from different
models and can disagree in direction. Commit `30b76bd` already did exactly this repair for the 2 h
headline; the trend was missed.

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java:132` (call site), `:405-419` (method)
- Test: `src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsServiceTest.java:82` (existing reflection test binds the OLD signature and must be updated in the same task, or it fails at runtime with NoSuchMethodException)

**Interfaces:**
- Consumes: `List<PredictionPointDTO> predictionPath` already built at `:138`; `PredictionPointDTO.getPredictedGlucose()`.
- Produces: `determineTrend(List<PredictionPointDTO> path, double currentGlucose, PredictionFactors factors, double horizonMinutes)` returning `"rising" | "falling" | "stable"`.

- [ ] **Step 1: Update the existing reflection test to the new signature**

`GlucoseCalculationsServiceTest:82` already binds this method:

```java
Method method = GlucoseCalculationsService.class.getDeclaredMethod("determineTrend", PredictionFactors.class, double.class);
```

That is reflection, so it compiles and fails at runtime with `NoSuchMethodException` once the
signature changes. Rewrite it to exercise the documented empty-path fallback — same thresholds, same
three assertions, new signature:

```java
    @Test
    void determineTrendUsesAdjustedThresholds() throws Exception {
        // Use a minimal service instance for the private-method reflection test
        GlucoseCalculationsService svc = new GlucoseCalculationsService(null, null, null, null, null, null, null, null, null);
        Method method = GlucoseCalculationsService.class.getDeclaredMethod(
                "determineTrend", List.class, double.class, PredictionFactors.class, double.class);
        method.setAccessible(true);

        PredictionFactors rising = PredictionFactors.builder().carbContribution(0.35).insulinContribution(0.0).baselineContribution(0.0).trendContribution(0.0).build();
        PredictionFactors falling = PredictionFactors.builder().carbContribution(-0.35).insulinContribution(0.0).baselineContribution(0.0).trendContribution(0.0).build();
        PredictionFactors stable = PredictionFactors.builder().carbContribution(0.1).insulinContribution(-0.1).baselineContribution(0.0).trendContribution(0.0).build();

        // Empty path -> documented fallback to the analytical factors.
        assertEquals("rising",  method.invoke(svc, List.of(), 7.0, rising, 120.0));
        assertEquals("falling", method.invoke(svc, List.of(), 7.0, falling, 120.0));
        assertEquals("stable",  method.invoke(svc, List.of(), 7.0, stable, 120.0));
    }
```

Add `import java.util.List;` if the class does not already have it.

- [ ] **Step 2: Write the failing test for the new behaviour**

Add to `GlucoseCalculationsServiceTest`, in the same reflection style as the test above (house style
for this private method):

```java
    @Test
    void trendFollowsThePredictionPathNotTheCarbRatioFormula() throws Exception {
        GlucoseCalculationsService svc = new GlucoseCalculationsService(null, null, null, null, null, null, null, null, null);
        Method method = GlucoseCalculationsService.class.getDeclaredMethod(
                "determineTrend", List.class, double.class, PredictionFactors.class, double.class);
        method.setAccessible(true);

        // 24 five-minute points; the 2h point (index 23) sits 3.0 mmol/L below "now".
        List<PredictionPointDTO> falling = new ArrayList<>();
        for (int i = 1; i <= 24; i++) {
            falling.add(PredictionPointDTO.builder()
                    .timestamp(LocalDateTime.now().plusMinutes(5L * i))
                    .predictedGlucose(i < 24 ? 7.0 : 4.0)
                    .build());
        }
        // The factors claim a strong rise; the path falls. The path must win.
        PredictionFactors risingFactors = PredictionFactors.builder()
                .carbContribution(5.0).insulinContribution(0.0)
                .baselineContribution(0.0).trendContribution(0.0).build();

        assertEquals("falling", method.invoke(svc, falling, 7.0, risingFactors, 120.0));
    }
```

Add `import java.util.ArrayList;` and `import che.glucosemonitorbe.dto.PredictionPointDTO;` if absent.

- [ ] **Step 3: Run both tests and confirm they fail**

```bash
./gradlew test --tests 'che.glucosemonitorbe.service.GlucoseCalculationsServiceTest' --offline
```

Expected: both fail with `NoSuchMethodException: determineTrend(List, double, PredictionFactors, double)`.
That is the correct red — the four-argument signature does not exist yet.

- [ ] **Step 4: Change the method to read the path**

Replace `determineTrend` at `GlucoseCalculationsService.java:405`:

```java
/**
 * Trend of the forecast the user is actually looking at: the change from "now" to the horizon
 * point of {@code path}. Previously derived from carbContribution, which is priced with
 * {@code carbRatio} - a parameter with no influence on the Hovorka path (audit finding F12), so
 * the chip could read "rising" while the curve beside it fell toward hypo. Falls back to the
 * analytical factors only when the path is unexpectedly empty, mirroring twoHourPrediction.
 */
private String determineTrend(List<PredictionPointDTO> path, double currentGlucose,
                              PredictionFactors factors, double horizonMinutes) {
    double netEffect;
    if (path != null && !path.isEmpty()) {
        int idx = (int) Math.round(horizonMinutes / PREDICTION_PATH_STEP_MINUTES) - 1;
        idx = Math.max(0, Math.min(idx, path.size() - 1));
        netEffect = path.get(idx).getPredictedGlucose() - currentGlucose;
    } else {
        netEffect = factors.getCarbContribution()
                  + factors.getInsulinContribution()
                  + factors.getBaselineContribution()
                  + factors.getTrendContribution()
                  + (factors.getPreBolusTimingContribution() != null
                        ? factors.getPreBolusTimingContribution() : 0.0);
    }

    if (netEffect > TREND_RISING_THRESHOLD) {
        return "rising";
    } else if (netEffect < TREND_FALLING_THRESHOLD) {
        return "falling";
    } else {
        return "stable";
    }
}
```

- [ ] **Step 5: Move the call site below path construction**

`determineTrend` is currently invoked at `:132`, six lines *before* `predictionPath` is built at
`:138`. Delete the line at `:132`:

```java
        String trend = determineTrend(factors, predictionHorizon);
```

and insert immediately after the `predictionPath` assignment ends (after the closing `);` at `:148`):

```java
        String trend = determineTrend(predictionPath, request.getCurrentGlucose(),
                factors, predictionHorizon);
```

- [ ] **Step 6: Run the tests and confirm they pass**

```bash
./gradlew test --tests 'che.glucosemonitorbe.service.GlucoseCalculationsServiceTest' --offline
```

Expected: PASS.

- [ ] **Step 7: Run the full suite**

```bash
./gradlew build --offline
```

Expected: 1002+ tests, only the 3 known DNS failures.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java \
        src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsServiceTest.java
git commit -m "fix(prediction): trend label must track the prediction path

determineTrend summed carbContribution, which prices carbs with carbRatio -
a parameter perturbation proves has zero influence on the Hovorka curve
(audit F12). The chip and the curve beside it were computed from different
models and could disagree in direction. Reads the path's horizon point now,
as twoHourPrediction already does since 30b76bd.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Bound the per-acceptance titration step

`gramsPerUnit = 10 × isf / carbRatio` is inversely proportional to `carbRatio`, and
`VerificationService` scales it by `clamp(1 + relError, 0.5, 2.0)`. One accepted suggestion can
therefore halve or double every subsequent meal bolus, bounded only by the wide 3–30 g/U refusal
envelope (at ISF 2.2 that permits `carbRatio` anywhere in 0.73–7.3). With the existing gates —
7-event window, consistency ≥ 0.60, |meanError| ≥ 0.5 — a ±25 % cap still converges quickly
(four acceptances compound to 2.4×) while no single acceptance can move a dosing parameter more than
a quarter.

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/VerificationService.java:210-212`
- Test: `src/test/java/che/glucosemonitorbe/service/VerificationServiceTest.java`

**Interfaces:**
- Consumes: existing private `refreshSummary` path and `VerificationSummary.getSuggestedCarbRatio()`.
- Produces: `public static final double MAX_CR_STEP = 0.25;` on `VerificationService`.

- [ ] **Step 1: Write the failing test**

Add to `VerificationServiceTest`:

```java
@Test
void suggestedCarbRatioNeverMovesMoreThanOneQuarterPerAcceptance() {
    double current = 2.0;
    // relError of +3.0 would previously scale by clamp(4.0, 0.5, 2.0) = 2.0 -> 4.00
    double scaledUp = VerificationService.boundedCarbRatioStep(current, 3.0);
    // relError of -3.0 would previously scale by clamp(-2.0, 0.5, 2.0) = 0.5 -> 1.00
    double scaledDown = VerificationService.boundedCarbRatioStep(current, -3.0);

    assertThat(scaledUp).isEqualTo(2.5);
    assertThat(scaledDown).isEqualTo(1.5);
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew test --tests 'che.glucosemonitorbe.service.VerificationServiceTest' --offline
```

Expected: compilation failure — `boundedCarbRatioStep` does not exist. Correct red.

- [ ] **Step 3: Extract and bound the step**

Add to `VerificationService`, next to the other thresholds near `:35`:

```java
/**
 * Largest fraction by which one accepted suggestion may move carbRatio.
 *
 * <p>gramsPerUnit = 10 x isf / carbRatio, so this is a bound on how far a single acceptance can
 * move every subsequent meal bolus. The former clamp of [0.5, 2.0] let one acceptance halve or
 * double the dose. With the 7-event window and the consistency gate, 25 % still converges in a
 * handful of acceptances.
 */
public static final double MAX_CR_STEP = 0.25;

/** carbRatio after one titration step, bounded to +/-{@link #MAX_CR_STEP}. */
public static double boundedCarbRatioStep(double currentCarbRatio, double relError) {
    double scale = Math.max(1.0 - MAX_CR_STEP, Math.min(1.0 + MAX_CR_STEP, 1.0 + relError));
    return Math.round(currentCarbRatio * scale * 100.0) / 100.0;
}
```

Replace the body at `:210-212`:

```java
            double relError = meanError / meanAbsPredicted;
            summary.setSuggestedCarbRatio(boundedCarbRatioStep(curCR, relError));
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew test --tests 'che.glucosemonitorbe.service.VerificationServiceTest' --offline
```

Expected: PASS.

- [ ] **Step 5: Run the full suite**

```bash
./gradlew build --offline
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/VerificationService.java \
        src/test/java/che/glucosemonitorbe/service/VerificationServiceTest.java
git commit -m "fix(verification): bound one accepted suggestion to a 25% carbRatio step

gramsPerUnit is inversely proportional to carbRatio, and the step was
clamp(1+relError, 0.5, 2.0) - so a single acceptance could halve or double
every subsequent meal bolus, checked only by the wide 3-30 g/U refusal
envelope. Audit finding F12.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: Delete the unreachable `suggestedIsf`

`suggestedIsf` is set to `null` at `VerificationService:190` and `:248` and assigned nowhere else, so
the `if (summary.getSuggestedIsf() != null) cob.setIsf(...)` branch at `:234` can never fire. The
field, the DTO and the "Carb Ratio Refinement" screen all imply an ISF half that does not exist.
Removing it is behaviour-preserving by construction. Audit finding F13.

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/VerificationService.java:190,234,248,358`
- Modify: `src/main/java/che/glucosemonitorbe/entity/VerificationSummary.java:37`
- Modify: `src/main/java/che/glucosemonitorbe/dto/VerificationSummaryDTO.java:20`
- Modify: `src/main/resources/db/migration/V6__verification_events.sql:29`

**Interfaces:**
- Consumes: nothing new.
- Produces: `VerificationSummaryDTO` no longer exposes `suggestedIsf`.

- [ ] **Step 1: Write the failing test**

Add to `VerificationServiceTest`:

```java
@Test
void acceptSuggestionWritesCarbRatioOnlyAndNeverTouchesIsf() {
    UUID userId = UUID.randomUUID();
    VerificationSummary summary = VerificationSummary.builder()
            .userId(userId).suggestedCarbRatio(2.4).build();
    UserSettings settings = new UserSettings();
    settings.setCarbRatio(2.0);
    settings.setIsf(2.2);
    when(verificationSummaryRepository.findById(userId)).thenReturn(Optional.of(summary));
    when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.of(settings));
    when(verificationEventRepository.findCompletedByUserId(userId)).thenReturn(List.of());

    service.acceptSuggestion(userId);

    assertThat(settings.getCarbRatio()).isEqualTo(2.4);
    assertThat(settings.getIsf()).isEqualTo(2.2);   // untouched
    // The dead field must no longer exist on the builder.
    assertThat(VerificationSummary.class.getDeclaredFields())
            .noneMatch(f -> f.getName().equals("suggestedIsf"));
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew test --tests 'che.glucosemonitorbe.service.VerificationServiceTest' --offline
```

Expected: FAIL on the final assertion — `suggestedIsf` is still declared.

- [ ] **Step 3: Remove the field and every reference**

In `VerificationSummary.java`, delete line 37:

```java
    private Double suggestedIsf;
```

In `VerificationSummaryDTO.java`, delete line 20:

```java
    private Double suggestedIsf;
```

In `VerificationService.java`, delete these four lines (at `:190`, `:234`, `:248`, `:358`):

```java
        summary.setSuggestedIsf(null);
        if (summary.getSuggestedIsf()       != null) cob.setIsf(summary.getSuggestedIsf());
        summary.setSuggestedIsf(null);
                .suggestedIsf(s.getSuggestedIsf())
```

Update the comment block above the titration at `:192-197` so it no longer implies a second knob:

```java
        // Attribute a *consistent* systematic error to a single knob. Every qualifying event is a
        // meal with a bolus (carbs 20-80 g, insulin > 0), so the 2 h net error cannot be split
        // between the carb-rise coefficient and the model ISF - adjusting both would double-count
        // the same miss. Only the carb ratio is corrected; there is deliberately no ISF suggestion.
```

In `V6__verification_events.sql`, delete line 29 from the `verification_summary` table:

```sql
    suggested_isf        DOUBLE PRECISION,
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
./gradlew test --tests 'che.glucosemonitorbe.service.VerificationServiceTest' --offline
```

Expected: PASS.

- [ ] **Step 5: Verify the migration against a clean database**

The Testcontainers suites build the schema from scratch, so they are the check:

```bash
./gradlew test --tests 'che.glucosemonitorbe.integration.VerificationE2ETest' --offline
```

Expected: PASS. If it errors on a Flyway checksum, the local dev database is populated — run
`flyway repair` or recreate it. Production never re-runs versioned migrations.

- [ ] **Step 6: Run the full suite**

```bash
./gradlew build --offline
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/VerificationService.java \
        src/main/java/che/glucosemonitorbe/entity/VerificationSummary.java \
        src/main/java/che/glucosemonitorbe/dto/VerificationSummaryDTO.java \
        src/main/resources/db/migration/V6__verification_events.sql \
        src/test/java/che/glucosemonitorbe/service/VerificationServiceTest.java
git commit -m "refactor(verification): remove the unreachable suggestedIsf

Set to null at two sites and assigned nowhere, so the acceptSuggestion
branch guarding it could never fire. The DTO and the refinement screen
implied an ISF half that did not exist. Audit finding F13.

Edits V6 in place per the schema rules; a populated dev database needs
flyway repair.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Correct the three javadocs that hid these bugs

Each of these documents behaviour the code does not have, and each is a reason a dead parameter went
unnoticed. No test — documentation only, verified by reading.

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameters.java:8`
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameterService.java:191-197`
- Modify: `src/main/java/che/glucosemonitorbe/entity/VerificationEvent.java:45`

- [ ] **Step 1: Fix `HovorkaParameters` line 8**

Replace:

```java
 * User-specific values (isf, carbRatio, weight) are derived from experiments and settings.</p>
```

with:

```java
 * User-specific values (isf, weight, carb half-life) are derived from experiments and settings.
 * {@code carbRatio} is deliberately NOT among them: it is a dosing coefficient
 * (gramsPerUnit = 10 x isf / carbRatio) with no reader anywhere in this package.</p>
```

- [ ] **Step 2: Fix the `applyScales` javadoc**

In `HovorkaParameterService.java`, replace the sentence claiming egpScale reaches the ODE:

```java
     * {@code egpScale} is fitted by the calibrator from
     * BASAL_CHECK fasting windows and now flows into the live ODE.
```

with:

```java
     * {@code egpScale} is fitted by the calibrator from BASAL_CHECK fasting windows but does NOT
     * reach the ODE: HovorkaGlucosePredictionService.buildWithParams recomputes egp0 from the
     * population constant and overwrites both egpNet and egp0 (audit finding F1). Perturbing
     * either field x0.5 vs x5 leaves the emitted curve bit-identical.
```

- [ ] **Step 3: Fix the `VerificationEvent` javadoc**

Replace line 45:

```java
    /** (carbs_g × carbRatio) − (insulin_u × isf) at time of note */
```

with:

```java
    /** (carbs_g × carbRatio / 10) − (insulin_u × isf) at time of note.
     *  carbRatio is mmol/L per 10 g, so the /10 converts it to per-gram. */
```

- [ ] **Step 4: Confirm the build still compiles**

```bash
./gradlew build --offline
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameters.java \
        src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameterService.java \
        src/main/java/che/glucosemonitorbe/entity/VerificationEvent.java
git commit -m "docs: correct three javadocs that described behaviour the code lacks

HovorkaParameters listed carbRatio as a model input (it has no reader in
the package), applyScales claimed egpScale flows into the live ODE (it is
overwritten before use), and VerificationEvent omitted the /10 the code
applies. Each is a reason a dead parameter went unnoticed. Audit F1, F12.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Verification

After all four tasks:

```bash
./gradlew build --offline
git status --porcelain    # must be empty
```

Expected: 1004 tests (1002 + 2 new), 3 failures — the known DNS trio (F16) and nothing else.

Confirm the S1 risk is closed by hand:

```bash
grep -n "MAX_CR_STEP" src/main/java/che/glucosemonitorbe/service/VerificationService.java
grep -rn "suggestedIsf" src/main src/test    # must return nothing
grep -n "determineTrend(predictionPath" src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java
```

## Out of scope — the remaining plans

| Plan | Findings | Note |
|---|---|---|
| 2 · Dashboard macro pipeline | F6, F2, F21 | Wire caloric density and fiber into the dashboard; decide which single channel owns the fat/protein slowdown. Largest behaviour change of the set. |
| 3 · Re-base the learning loop | F12 (remainder), F1, F3, F10 | Measure verification error against the real prediction path and titrate `agScale`; wire or delete `egpScale`, `isfScale`, `tMaxGScale`. Depends on Plan 2 settling carb magnitude. |
| 4 · Pattern matching and GI/GL | F9, F11, F5, F20 | Fiber/FPU exclusivity, unreachable `Fast Spike`, null-as-zero, the four GI defaults, carb-weighted GI. |
| 5 · iOS client | F18, F19 | "4h forecast" rendering the 8 h value; scan mode discarding manual macro edits. Separate repo. |
| 6 · Housekeeping | F4, F7, F8, F14, F15, F16, F17 | Basal dose ignored, horizon, mutable static lag, confidence, live-DNS tests, dead uncertainty band. |
