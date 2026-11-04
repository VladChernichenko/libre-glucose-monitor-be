# Meaning Corrections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make six clinical names match the numbers the backend actually computes, without reinterpreting stored `carb_ratio` values and without treating a missing basal log as zero basal.

**Architecture:** Each task is one TDD cycle on the live path that already owns the quantity. Basal suppression scales with logged units up to the existing 0.40 ceiling. An optional `grams_per_unit` is used for the meal dose when present; otherwise `10 * isf / carb_ratio` is unchanged. The live bolus term multiplies by the applied `isfScale`. The ODE uses the already-scaled `egp0` instead of rebuilding it from the population constant. Headline `confidence` is a function of the 2-hour uncertainty, and a missing glycemic index no longer matches a GI-bounded meal pattern.

**Tech Stack:** Java 21, Spring Boot, Gradle, JUnit 5, AssertJ, Mockito, Flyway, PostgreSQL.

**Spec:** The meaning section of the backend architecture review (findings 5-8): a logged basal dose is a clock, `carbRatio` is mmol/L per 10 g rather than g/U, `isfScale` and `egpScale` do not reach the live curve, and `confidence` / a missing GI do not mean what they are called. Source tree: `glucose-monitor-be` on `main` at `8ff385f`.

## Global Constraints

- TDD: write the failing test, run it, confirm the failure is the missing behavior, then the smallest production change. No production edit before that red run.
- `carb_ratio` stays mmol/L of rise per 10 g. Do not migrate or rescale existing rows. `gramsPerUnit = 10 * isf / carbRatio` remains the formula whenever `grams_per_unit` is null.
- Grams-per-unit envelope stays `[3.0, 30.0]`. A value outside it refuses with `DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT`. It is not clamped.
- Basal peak stays `BasalInsulinResolver.PEAK_X3_BASAL` (0.40). Reference dose is `0.3` U/kg (`REFERENCE_UNITS_PER_KG`). The 3-argument overload keeps using `DEFAULT_REFERENCE_UNITS = 20.0`, so a 20 U note on that overload still returns the full peak.
- A logged dose above the reference does not suppress more than 0.40. A note with insulin `<= 0` is not an active dose.
- No long-acting note, and a note past duration + wane, still mean "the log is silent": `buildWithParams` sets EGP equal to F01. Do not remove that branch.
- `isfScale` is clamped with `TwinScales.clamped()` (`0.5` to `2.0`) before it multiplies a bolus ISF. `PredictionResidualProvider.NONE` returns `1.0`.
- Headline confidence is `max(0.5, min(0.95, 1 - sd / 5))`. A missing `uncertaintySd` uses `2.3` (the population prior at 120 min). Note count and the current glucose value do not enter it.
- A null glycemic index skips GI min/max checks. It is not treated as 0. A stated GI of 40 still matches Slow Climb.
- Schema: never `ALTER TABLE` in a new migration. `user_settings` is owned by `src/main/resources/db/migration/V1__baseline_schema.sql`. Edit that file. The commit body must say a populated database needs `flyway repair`.
- Run only the test class named in the task until that class is green, then `./gradlew test` before the task's commit.
- Do not commit unless the user has asked for commits. When they have, one commit per task, message as written.

## Out of scope

These are separate plans. Do not start them here.

- One physiological model for the chart, the empirical series, the OpenAPS fallback, and the meal-dose formula.
- Moving carb advice and the watch strip out of `GlucoseCalculationsService`.
- Splitting `notes` into separate event types.
- Replacing wall-clock `LocalDateTime` with instants, including the snapshot that anchors `savedAt` to `Instant.now()`.
- Treating a missing basal log as no basal. The live comment records that this raises fasting glucose by about 9 mmol/L in 4 hours.
- Wiring `tMaxGScale`. The calibrator stores 1.0 and `TwinScales` documents it as reserved.
- Changing the enrichment default of 55 for an unrecognized food name. That retunes pizza-versus-pasta ordering. This plan only stops a missing GI from being read as 0.

## File structure

| File | Responsibility |
|---|---|
| `hovorka/BasalInsulinResolver.java` | Suppression from long-acting notes. Gains a reference-dose argument. |
| `hovorka/HovorkaGlucosePredictionService.java` | Passes `0.3 * weight` into the resolver, multiplies bolus ISF by `isfScale`, uses `p.egp0()`. |
| `hovorka/learning/PredictionResidualProvider.java` | Adds `isfScale(UUID)`. |
| `service/DigitalTwinResidualProvider.java` | Returns the applied twin's clamped `isfScale`, else 1. |
| `entity/UserSettings.java`, `dto/UserSettingsDTO.java`, `service/UserSettingsService.java` | Nullable `gramsPerUnit`, copied on save and on read. |
| `service/InsulinCalculatorService.java` | Uses `gramsPerUnit` when set. |
| `V1__baseline_schema.sql` | Nullable `grams_per_unit` on `user_settings`. |
| `service/GlucoseCalculationsService.java` | `confidenceFromUncertainty`. |
| `service/nutrition/GlycemicPatternMatchingService.java` | Null GI skips GI bounds. |
| Tests named in each task | The red step. |

## Review focus

- A 40 U basal on a 20 U reference must stay at 0.40, not 0.80. Task 1.
- `gramsPerUnit` of 2.2 or 40 must refuse, not be clamped into 3-30. Task 2.
- `isfScale` of 3.0 must hit the curve as 2.0, matching `TwinScales.MAX_SCALE`. Task 3.
- Doubling `egp0` while no basal note is logged must leave the 4 h fasting glucose at 6.0. Task 4.
- Glucose 2.8 and glucose 6.5 with the same uncertainty must produce the same confidence. Task 5.
- GI 40, no fat and no protein, must still match Slow Climb. Task 6 keeps the existing test `pureSlowCarbs_lowGI_matchesSlowClimb` green.

---

### Task 1: Logged basal units scale suppression

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/BasalInsulinResolver.java`
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java` (the `resolveEgpSuppression` call inside `buildWithParams`)
- Test: `src/test/java/che/glucosemonitorbe/hovorka/BasalInsulinResolverTest.java`

**Interfaces:**
- Consumes: `PEAK_X3_BASAL`, `Note.getInsulin()`, `HovorkaParameters.weightKg()`.
- Produces: `BasalInsulinResolver.REFERENCE_UNITS_PER_KG = 0.3`, `DEFAULT_REFERENCE_UNITS = 20.0`, and `resolveEgpSuppression(List<Note> notes, LocalDateTime now, double durationHours, double referenceUnits)`. The existing 3-argument method delegates with `DEFAULT_REFERENCE_UNITS`. `hasActiveDose` ignores notes whose insulin is null or `<= 0`.

- [ ] **Step 1: Write the failing test**

Add to `BasalInsulinResolverTest`, reusing `dose` and `NOW`:

```java
@Test
@DisplayName("six units on a 20 U reference suppresses 6/20 of the peak; 20 U is the peak")
void unitsScaleSuppressionUpToTheReference() {
    Note six = dose(6); six.setInsulin(6);
    Note ref = dose(6); ref.setInsulin(20);
    assertThat(resolver.resolveEgpSuppression(List.of(six), NOW, GLARGINE_H, 20.0))
            .isCloseTo(PEAK * 6.0 / 20.0, within(1e-9));
    assertThat(resolver.resolveEgpSuppression(List.of(ref), NOW, GLARGINE_H, 20.0))
            .isEqualTo(PEAK);
}

@Test
@DisplayName("a dose above the reference does not exceed the peak")
void doseAboveReferenceStaysAtPeak() {
    Note big = dose(6); big.setInsulin(40);
    assertThat(resolver.resolveEgpSuppression(List.of(big), NOW, GLARGINE_H, 20.0))
            .isEqualTo(PEAK);
}

@Test
@DisplayName("zero units is not an active dose")
void zeroUnitsIsNotActive() {
    Note zero = dose(6); zero.setInsulin(0);
    assertThat(resolver.hasActiveDose(List.of(zero), NOW, GLARGINE_H)).isFalse();
    assertThat(resolver.resolveEgpSuppression(List.of(zero), NOW, GLARGINE_H, 20.0))
            .isEqualTo(0.0);
}

@Test
@DisplayName("a below-reference basal lets fasting glucose rise above the reference dose")
void belowReferenceBasalRaisesFastingForecast() {
    Note six = dose(6); six.setInsulin(6);
    Note ref = dose(6); ref.setInsulin(21); // 0.3 U/kg at the helper's 70 kg
    assertThat(fastingGlucoseAt4h(List.of(six), GLARGINE_H))
            .isGreaterThan(fastingGlucoseAt4h(List.of(ref), GLARGINE_H) + 0.3);
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests che.glucosemonitorbe.hovorka.BasalInsulinResolverTest`

Expected: compile failure, or assertion failure on `unitsScaleSuppressionUpToTheReference` because 6 U and 20 U both return `PEAK`. `forecastFlatUnlessOverdue` must still be the old behavior until Step 3.

- [ ] **Step 3: Implement the reference-dose fraction**

`fraction = clamp(units / referenceUnits, 0, 1)` multiplies `suppressionCurve`. Non-finite or `<= 0` reference uses `DEFAULT_REFERENCE_UNITS`. Skip notes with insulin null or `<= 0` in both `resolveEgpSuppression` and `hasActiveDose`. In `buildWithParams`, pass `REFERENCE_UNITS_PER_KG * p.weightKg()`. Change the test helper `dose()` from 20 U to 21 U so an on-schedule note is exactly the 70 kg reference the forecast helper uses. The 3-argument assertions still see the peak, because 21 U against the 20 U default reference clamps to 1.

- [ ] **Step 4: Run the test**

Run the same class. Expected: PASS, including `forecastFlatUnlessOverdue` (empty log and an on-schedule reference dose stay near 6.0; an overdue dose still rises).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/hovorka/BasalInsulinResolver.java \
        src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java \
        src/test/java/che/glucosemonitorbe/hovorka/BasalInsulinResolverTest.java
git commit -m "$(cat <<'EOF'
fix(basal): scale EGP suppression by the logged dose

A 6 U and a 40 U long-acting note were the same 0.40 suppression. Units now scale that plateau up to a 0.3 U/kg reference and stop there. A missing log still assumes therapeutic basal.

EOF
)"
```

---

### Task 2: Optional grams-per-unit dose

**Files:**
- Modify: `src/main/resources/db/migration/V1__baseline_schema.sql` (the `user_settings` table)
- Modify: `src/main/java/che/glucosemonitorbe/entity/UserSettings.java`
- Modify: `src/main/java/che/glucosemonitorbe/dto/UserSettingsDTO.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/UserSettingsService.java` (`updateSettings`, `convertToDTO`)
- Modify: `src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java` (`resolveGramsPerUnit`)
- Test: `src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceTest.java`

**Interfaces:**
- Consumes: `UserSettingsDTO.getCarbRatio()`, `getEffectiveIsf`, `DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT`.
- Produces: `UserSettingsDTO.getGramsPerUnit(): Double`. Null means derive. `resolveGramsPerUnit` returns the stored value when it is inside `[3.0, 30.0]`.

- [ ] **Step 1: Write the failing test**

Add a 4-argument overload next to the existing `givenSettings`. It builds the same 7-arg `UserSettingsDTO`, calls `setGramsPerUnit`, and stubs `getUserSettings` the same way. Use the existing `doseRequest` and `refusalReasonOf`.

```java
private void givenSettings(Double carbRatio, Double isf, Double bodyWeightKg, Double gramsPerUnit) {
    UserSettingsDTO settings = new UserSettingsDTO(
            UUID.randomUUID(), USER_ID, carbRatio, isf, 45, 240, bodyWeightKg);
    settings.setGramsPerUnit(gramsPerUnit);
    when(userSettingsService.getUserSettings(any(UUID.class))).thenReturn(settings);
}

@Test
void recommendedInsulin_explicitGramsPerUnit_ignoresCarbRatio() {
    givenSettings(2.0, 2.2, 70.0, 10.0); // carbRatio would derive 11 g/U
    var response = service.calculateRecommendedInsulin(doseRequest(60.0, 6.0, 6.0));
    assertThat(response.getRecommendedInsulin()).isEqualTo(6.0); // 60 / 10
}

@Test
void recommendedInsulin_nullGramsPerUnit_keepsDerivedRatio() {
    givenSettings(2.0, 2.2, 70.0, null);
    var response = service.calculateRecommendedInsulin(doseRequest(60.0, 6.0, 6.0));
    assertThat(response.getRecommendedInsulin()).isEqualTo(5.45); // 60 / 11, rounded to 0.01
}

@Test
void recommendedInsulin_gramsPerUnitOutsideEnvelope_refuses() {
    givenSettings(2.0, 2.2, 70.0, 2.2);
    assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(60.0, 6.0, 6.0)))
            .isInstanceOf(DosingRefusedException.class)
            .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
            .isEqualTo(DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT);
    givenSettings(2.0, 2.2, 70.0, 40.0);
    assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(60.0, 6.0, 6.0)))
            .isInstanceOf(DosingRefusedException.class)
            .extracting(InsulinCalculatorServiceTest::refusalReasonOf)
            .isEqualTo(DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT);
}
```

Do not change tests that never set `gramsPerUnit`.

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests che.glucosemonitorbe.service.InsulinCalculatorServiceTest`

Expected: compile failure (`getGramsPerUnit` / the new `givenSettings` overload missing).

- [ ] **Step 3: Add the nullable column and the branch**

In `V1__baseline_schema.sql`, on `user_settings`, add `grams_per_unit DOUBLE PRECISION` and `CONSTRAINT chk_user_settings_grams_per_unit CHECK (grams_per_unit IS NULL OR (grams_per_unit >= 3 AND grams_per_unit <= 30))`. Map it on the entity and DTO. `updateSettings` writes it when the DTO field is non-null. `convertToDTO` sets it after the constructor, the same way `setTargetGlucoseMmol` is set. `resolveGramsPerUnit` returns the stored value when non-null and inside the envelope, and throws `INSULIN_PARAMS_INCONSISTENT` when non-null and outside it. Null falls through to `10 * isf / carbRatio`.

- [ ] **Step 4: Run the test**

Same class. Expected: PASS, including the existing derived-ratio tests.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V1__baseline_schema.sql \
        src/main/java/che/glucosemonitorbe/entity/UserSettings.java \
        src/main/java/che/glucosemonitorbe/dto/UserSettingsDTO.java \
        src/main/java/che/glucosemonitorbe/service/UserSettingsService.java \
        src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java \
        src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceTest.java
git commit -m "$(cat <<'EOF'
fix(dose): accept an explicit grams-per-unit ratio

carb_ratio remains mmol/L per 10 g and still derives the dose when grams_per_unit is null. A stored grams-per-unit is the meal dose's carb ratio, and a value outside 3-30 is refused.

A populated database needs flyway repair because V1 changed.

EOF
)"
```

---

### Task 3: The forecast bolus uses isfScale

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/learning/PredictionResidualProvider.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/DigitalTwinResidualProvider.java`
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java` (`resolveIsf` / `buildDoseActivities`)
- Test: `src/test/java/che/glucosemonitorbe/hovorka/BasalInsulinResolverTest.java` is the wrong home. Add the test to `src/test/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionServiceTest.java`. If that class has no package-visible builder for a one-bolus curve, add the test beside `fastingGlucoseAt4h` in `BasalInsulinResolverTest` by giving the helper an extra `PredictionResidualProvider` argument. Prefer extending the helper already in `BasalInsulinResolverTest` so the setup is not copied.

**Interfaces:**
- Consumes: `TwinScales.clamped().isfScale()`, `DigitalTwinService.activeScales(UUID)`.
- Produces: `PredictionResidualProvider.isfScale(UUID userId)` defaulting to `1.0`. `NONE` stays `1.0` via that default. `DigitalTwinResidualProvider.isfScale` returns the applied scale, or `1.0` when `activeScales` is empty or `userId` is null.

- [ ] **Step 1: Write the failing test**

```java
@Test
@DisplayName("an isfScale of 2 pulls a bolus forecast down; 3 is clamped to 2")
void bolusForecastUsesClampedIsfScale() {
    InsulinDose bolus = InsulinDose.builder()
            .units(4.0).timestamp(NOW.minusMinutes(60)).build();
    double atScale1 = glucoseAt4h(List.of(bolus), scale(1.0));
    double atScale2 = glucoseAt4h(List.of(bolus), scale(2.0));
    double atScale3 = glucoseAt4h(List.of(bolus), scale(3.0));
    assertThat(atScale2).isLessThan(atScale1 - 0.4);
    assertThat(atScale3).isCloseTo(atScale2, within(1e-6));
}
```

`scale(double)` is a `PredictionResidualProvider` whose `residualMmol` returns 0, `uncertaintySdMmol` returns 0, and `isfScale` returns the argument. `glucoseAt4h` is `fastingGlucoseAt4h`'s setup with that provider and the bolus list, settings null, no carbs, no basal, duration `GLARGINE_H`. Today both scales return the same glucose.

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests che.glucosemonitorbe.hovorka.BasalInsulinResolverTest` or the Hovorka class if the test landed there.

Expected: compile failure on `isfScale`, or equal 4 h glucoses.

- [ ] **Step 3: Multiply the dose ISF by the provider scale**

Add the default method. `DigitalTwinResidualProvider` reads `activeScales`, which is already clamped by `DigitalTwinService.load`. In `buildDoseActivities`, after `resolveIsf`, multiply by `residualProvider.isfScale(userId)`. Pass `userId` into that method. `NONE` needs no override.

- [ ] **Step 4: Run the test**

Expected: PASS. Existing forecast tests that use `NONE` stay on scale 1.

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
fix(twin): apply isfScale to the live bolus term

The watch correction already multiplied settings ISF by the applied scale. The forecast bolus term used the raw settings ISF whenever one was present. Both now use the clamped scale.

EOF
)"
```

---

### Task 4: egpScale survives into the ODE

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java` (`buildWithParams`, the `egp0Abs` line)
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameterService.java` (the `applyScales` javadoc that says the multiply is discarded)
- Test: `src/test/java/che/glucosemonitorbe/hovorka/BasalInsulinResolverTest.java`

**Interfaces:**
- Consumes: `HovorkaParameters.egp0()`, already multiplied by `egpScale` in `HovorkaParameterService.applyScales`. `HovorkaParameters.f01()`.
- Produces: no new method. `buildWithParams` uses `p.egp0()` as `egp0Abs`. The no-active-dose branch still assigns `egpNow = p.f01()`.

- [ ] **Step 1: Write the failing test**

```java
@Test
@DisplayName("doubling egp0 raises fasting glucose when a basal dose is on schedule")
void scaledEgp0ReachesTheForecast() {
    Note onSchedule = dose(6); onSchedule.setInsulin(21);
    assertThat(fastingGlucoseAt4h(List.of(onSchedule), GLARGINE_H, 2.0))
            .isGreaterThan(fastingGlucoseAt4h(List.of(onSchedule), GLARGINE_H, 1.0) + 0.5);
}

@Test
@DisplayName("doubling egp0 does nothing when the basal log is empty")
void scaledEgp0DoesNotMoveASilentLog() {
    assertThat(fastingGlucoseAt4h(List.of(), GLARGINE_H, 2.0)).isEqualTo(6.0);
    assertThat(fastingGlucoseAt4h(List.of(), GLARGINE_H, 1.0)).isEqualTo(6.0);
}
```

Extend `fastingGlucoseAt4h` with `double egpScale`. The `HovorkaParameters` it builds sets both `egpNet` and `egp0` to `EGP0_PER_KG * weight * egpScale`. The existing caller passes `1.0`.

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests che.glucosemonitorbe.hovorka.BasalInsulinResolverTest`

Expected: `scaledEgp0ReachesTheForecast` fails because both curves are about 6.0. The silent-log test already passes and must stay passing.

- [ ] **Step 3: Use `p.egp0()`**

Replace `HovorkaParameters.EGP0_PER_KG * p.weightKg()` at the `egp0Abs` assignment with `p.egp0()`. Leave the `hasActiveDose` fallback on `p.f01()`. Update the `applyScales` javadoc so it no longer says the scaled EGP is discarded.

- [ ] **Step 4: Run the test**

Expected: PASS. `forecastFlatUnlessOverdue` still passes at scale 1.

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
fix(twin): let egpScale reach the live ODE

buildWithParams rebuilt EGP from the population constant and dropped the fitted scale. An on-schedule basal now uses the scaled egp0. An empty basal log still assumes F01.

EOF
)"
```

---

### Task 5: Confidence reads uncertainty, not note count

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java` (`calculateConfidence` and its call)
- Test: `src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsServiceTest.java`

**Interfaces:**
- Consumes: `PredictionPointDTO.getUncertaintySd()` on the same index `twoHourPrediction` uses. Population stand-in `2.3`.
- Produces: package-visible `static double confidenceFromUncertainty(double uncertaintySdMmol)` returning `max(0.5, min(0.95, 1 - sd / 5))`. Non-finite or negative `sd` returns `0.5`. The response field `confidence` is that function of the 2-hour point, or of `2.3` when the path is empty or the sd is null.

- [ ] **Step 1: Replace the two note-count tests**

Delete the bodies of `confidence_hypoGlucose_reducedBelowNormal` and `confidence_zeroDataPoints_returnsLowConfidence`. Replace them with:

```java
@Test
void confidenceFromUncertainty_matchesThePinnedLine() {
    assertThat(GlucoseCalculationsService.confidenceFromUncertainty(0.0)).isEqualTo(0.95);
    assertThat(GlucoseCalculationsService.confidenceFromUncertainty(2.3)).isCloseTo(0.54, within(1e-9));
    assertThat(GlucoseCalculationsService.confidenceFromUncertainty(4.5)).isEqualTo(0.5);
    assertThat(GlucoseCalculationsService.confidenceFromUncertainty(Double.NaN)).isEqualTo(0.5);
}

@Test
void confidence_extremeGlucose_matchesNormal_whenUncertaintyMatches() {
    String username = "hypo_user";
    UUID userId = UUID.randomUUID();
    stubFullPipeline(username, userId);
    var meal = ProspectiveNoteDTO.builder().carbs(40.0).meal("Lunch").build();
    var hypo = service.calculateGlucoseData(GlucoseCalculationsRequest.builder()
            .currentGlucose(2.8).userId(username).prospectiveNotes(List.of(meal)).build());
    var normal = service.calculateGlucoseData(GlucoseCalculationsRequest.builder()
            .currentGlucose(6.5).userId(username).prospectiveNotes(List.of(meal)).build());
    assertThat(hypo.getConfidence()).isEqualTo(normal.getConfidence());
}

@Test
void confidence_noteCount_doesNotChangeTheHeadline() {
    String username = "no_data";
    UUID userId = UUID.randomUUID();
    stubFullPipeline(username, userId);
    var empty = service.calculateGlucoseData(GlucoseCalculationsRequest.builder()
            .currentGlucose(7.0).userId(username).build());
    var notes = new ArrayList<ProspectiveNoteDTO>();
    for (int i = 0; i < 5; i++) {
        notes.add(ProspectiveNoteDTO.builder().insulin(1.0).meal("Lunch").build());
    }
    var crowded = service.calculateGlucoseData(GlucoseCalculationsRequest.builder()
            .currentGlucose(7.0).userId(username).prospectiveNotes(notes).build());
    assertThat(crowded.getConfidence()).isEqualTo(empty.getConfidence());
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests che.glucosemonitorbe.service.GlucoseCalculationsServiceTest`

Expected: `confidenceFromUncertainty_matchesThePinnedLine` does not compile, or `confidence_extremeGlucose_matchesNormal_whenUncertaintyMatches` fails because 2.8 mmol is multiplied by 0.8.

- [ ] **Step 3: Implement `confidenceFromUncertainty` and call it**

Remove the note-count and glucose-extreme branches from the old private method. Call the new function with the 2-hour point's `uncertaintySd`, using `2.3` when that point or its sd is missing. Do not read `carbsEntries.size()` or `currentGlucose` for this field.

- [ ] **Step 4: Run the test**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
fix(forecast): score confidence from the 2h uncertainty

The headline was 0.5, 0.7, or 0.9 from how many notes were in the window. It now follows the 2-hour uncertainty band, and a low glucose reading no longer lowers it by itself.

EOF
)"
```

---

### Task 6: A missing GI is not GI 0

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/nutrition/GlycemicPatternMatchingService.java` (`findBestMatch` / the GI comparisons around the `gi` local)
- Test: `src/test/java/che/glucosemonitorbe/service/nutrition/GlycemicPatternMatchingServiceTest.java`

**Interfaces:**
- Consumes: `NutritionSnapshot.getEstimatedGi()`, pattern `giMin` / `giMax`.
- Produces: no new type. When `estimatedGi` is null, a pattern with a non-null `giMin` or `giMax` does not match. Fat, protein, fiber, and GL checks are unchanged. The ODE's null-GI substitute stays `HovorkaOdeSolver.DEFAULT_GI` (70). Do not change `NutritionEnrichmentService.DEFAULT_GI`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void missingGi_doesNotMatchSlowClimb() {
    NutritionSnapshot s = NutritionSnapshot.builder()
            .estimatedGi(null)
            .glycemicLoad(12.0)
            .fiber(0.0)
            .protein(1.0)
            .fat(1.0)
            .build();
    assertThat(service.enrich(s).getPatternName()).isNull();
}
```

Keep `pureSlowCarbs_lowGI_matchesSlowClimb` (GI 40) as it is.

- [ ] **Step 2: Run the test**

Run: `./gradlew test --tests che.glucosemonitorbe.service.nutrition.GlycemicPatternMatchingServiceTest`

Expected: FAIL. The name is `Slow Climb` because null is read as 0, and Slow Climb's range is 0-55.

- [ ] **Step 3: Skip GI bounds when the snapshot has no GI**

In the match predicate, when `estimatedGi` is null and the pattern has a `giMin` or a `giMax`, return false before the numeric comparison. Do not substitute 0.

- [ ] **Step 4: Run the test**

Expected: PASS, including `pureSlowCarbs_lowGI_matchesSlowClimb` and `pureFastCarbs_highGI_matchesFastSpike`.

- [ ] **Step 5: Run the full suite and commit**

Run: `./gradlew test`

Expected: the suite is green apart from failures already present on `main` and unrelated to these classes. Name any such failure in the commit body. Do not "fix" it here.

```bash
git commit -m "$(cat <<'EOF'
fix(nutrition): a missing GI no longer matches Slow Climb

The pattern matcher treated a null glycemic index as 0, which sits inside Slow Climb. GI bounds now apply only when the snapshot has a GI. Fat and protein patterns are unchanged.

EOF
)"
```
