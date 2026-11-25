# Carb Ratio per Meal Window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the insulin-to-carb ratio vary by meal window, use the window's ratio everywhere a ratio is read, learn per-window ratios from clean meals, and suggest them on the existing morning card next to ISF.

**Architecture:** Four nullable `grams_per_unit_*` columns on `user_settings`, resolved through `UserSettingsDTO` exactly like the ISF windows. Consumers (calculator, Hovorka meal factor, empirical twin) switch to the window value. The ISF learner keeps only correction events; a new ratio learner keeps only meal events and stores per-window medians in a new snapshot table. The suggestion service merges both into one card, and accept applies ratios only when the client opts in.

**Tech Stack:** Spring Boot 3 / Java 21 / JPA / Flyway / JUnit 5 + Mockito + AssertJ (backend); SwiftUI / XCTest (iPhone).

**Spec:** `docs/superpowers/specs/2026-11-25-carb-ratio-meal-windows-design.md` (backend repo). Read it before starting; this plan argues from it.

## Global Constraints

- Branch `feat/carb-ratio-meal-windows` in **both** repos, cut from `origin/main`, in sibling worktrees: `/Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be-carb-ratio` and `/Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-iphone-carb-ratio`. Never touch the uncommitted late-protein-rise work in the main iPhone checkout.
- Backend commands need `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`; integration tests need Docker running.
- Schema: `user_settings` columns are added by editing `V1__baseline_schema.sql` in place plus `scripts/upgrade-existing-db.sql`; the new table gets `V23__carb_ratio_meal_window_snapshots.sql`. Do **not** edit `V7` (not even its comments).
- A ratio is valid only in 3–30 g/U: DB CHECK, calculator refusal, learner range, suggestion output.
- Windows are `che.glucosemonitorbe.domain.MealWindow`: BREAKFAST 05–11, LUNCH 11–16, DINNER 16–22, NIGHT 22–05 (start inclusive, end exclusive).
- A learned value never reaches `user_settings` except through suggestion accept.
- Run only the tests named in each task while working; run the full backend suite and full iOS tests once in Task 11. Known pre-existing iOS failures: `HypoPromptTests` (3) and `BackendAPIBugTests.testJWTStorageUsesKeychain` under `CODE_SIGNING_ALLOWED=NO`.
- Never pipe `xcodebuild` into `tail`/`head` before checking `$?`; redirect to a log and grep for `** TEST SUCCEEDED **` / `** BUILD SUCCEEDED **`.
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

Backend test command template (run from the backend worktree):
`./gradlew test --tests '<fully.qualified.TestClass>'`

iPhone test command template (run from the iPhone worktree):
`xcodebuild test -scheme GlucoseMonitor -project GlucoseMonitor.xcodeproj -destination 'platform=iOS Simulator,name=iPhone 16,OS=18.3.1' -derivedDataPath /tmp/gm-derived CODE_SIGNING_ALLOWED=NO -only-testing:GlucoseMonitorTests/<TestClass> > /tmp/gm-test.log 2>&1; echo "exit=$?"; grep -E '\*\* TEST (SUCCEEDED|FAILED) \*\*' /tmp/gm-test.log`

## Review Focus

1. **Pre-bolus logged as its own note 20 min before the meal note** (the app has a pre-bolus timer, so this is the common case) → the bolus must anchor that meal. Test: Task 6 `preBolusLoggedSeparatelyAnchorsTheMeal`.
2. **Window ratios set but the single ratio is null** → the calculator prices a breakfast with the breakfast ratio and refuses a lunch with no ratio. Test: Task 2 `windowRatioWorksWithoutASingleRatio`.
3. **A request at exactly 11:00** → priced with the lunch ratio, not breakfast. Test: Task 2 `elevenOClockIsPricedAsLunch`.
4. **A meal after midnight** (00:30) → bucketed into NIGHT, its insulin window spans into the morning. Test: Task 6 `mealAfterMidnightBucketsIntoNight`.
5. **Learned ratio while the user has no usable current ratio** → proposal is the learned value, uncapped (still within 3–30) and counted as a change. Test: Task 7 `ratioLine_withoutCurrentRatio_isUncappedAndMaterial`.

---

### Task 0: Workspaces and the spec on the branch

**Files:**
- Move: `docs/superpowers/specs/2026-11-25-carb-ratio-meal-windows-design.md` and `docs/superpowers/plans/2026-11-25-carb-ratio-meal-windows.md` from the main backend checkout (untracked there) into the backend worktree.

- [ ] **Step 1: Create both worktrees** with superpowers:using-git-worktrees, branch `feat/carb-ratio-meal-windows` from a freshly fetched `origin/main`, at the paths in Global Constraints.
- [ ] **Step 2: Move the two docs** into the backend worktree (same relative paths) and delete the untracked copies from the main checkout. Leave the other untracked plan there (`2026-11-20-meal-absorption-gamma-and-advice-guard.md`) alone.
- [ ] **Step 3: Baseline compile**

Run: `./gradlew compileTestJava` (backend worktree). Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-11-25-carb-ratio-meal-windows-design.md docs/superpowers/plans/2026-11-25-carb-ratio-meal-windows.md
git commit -m "docs: carb ratio per meal window - design and plan"
```

---

### Task 1: Window ratios in settings

**Files:**
- Modify: `src/main/resources/db/migration/V1__baseline_schema.sql` (`user_settings` block)
- Modify: `scripts/upgrade-existing-db.sql` (user_settings section)
- Modify: `src/main/java/che/glucosemonitorbe/entity/UserSettings.java`
- Modify: `src/main/java/che/glucosemonitorbe/dto/UserSettingsDTO.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/UserSettingsService.java` (`updateSettings`, `convertToDTO`, the two log lines)
- Create: `src/test/java/che/glucosemonitorbe/dto/UserSettingsDTOWindowRatioTest.java`
- Modify: `src/test/java/che/glucosemonitorbe/service/UserSettingsServiceTest.java`
- Modify: `src/test/java/che/glucosemonitorbe/integration/ExistingDbUpgradeIT.java`

**Interfaces:**
- Produces (on `UserSettingsDTO`, Lombok getters/setters for the fields):
  - fields `Double gramsPerUnitBreakfast, gramsPerUnitLunch, gramsPerUnitDinner, gramsPerUnitNight`
  - `Double getWindowGramsPerUnit(MealWindow window)`: override for `window`, else `gramsPerUnit`; may be null
  - `Double getWindowGramsPerUnit(LocalDateTime time)`: same for `time`'s window (falls back to `gramsPerUnit` when `time` is null)
  - `Double getEffectiveGramsPerUnit(LocalDateTime time)`: `getWindowGramsPerUnit(time) × sick multiplier at time` (null stays null)
  - `Map<MealWindow, Double> getEffectiveGramsPerUnitByWindow(LocalDateTime time)`: `EnumMap`, each window's `getWindowGramsPerUnit(window) × sick multiplier at time`; windows whose value is null are absent. Annotate `@JsonIgnore` (it is not a property).

- [ ] **Step 1: Write the failing DTO tests** in `UserSettingsDTOWindowRatioTest`

```java
// settings: gramsPerUnit 10.0, gramsPerUnitBreakfast 8.0, gramsPerUnitDinner 12.0
@Test void windowRatioOverridesTheSingleRatio() {
    assertThat(s.getWindowGramsPerUnit(LocalDateTime.of(2026, 11, 25, 8, 0))).isEqualTo(8.0);
    assertThat(s.getWindowGramsPerUnit(LocalDateTime.of(2026, 11, 25, 12, 0))).isEqualTo(10.0);
    assertThat(s.getWindowGramsPerUnit(MealWindow.DINNER)).isEqualTo(12.0);
    assertThat(s.getWindowGramsPerUnit(MealWindow.NIGHT)).isEqualTo(10.0);
}
@Test void effectiveRatioIsTheWindowRatioScaledWhileSick() {
    // MODERATE span as in UserSettingsDTOSickTest (multiplier 0.7), breakfast hour inside it
    assertThat(sick.getEffectiveGramsPerUnit(insideAt8)).isCloseTo(5.6, within(1e-9));
    assertThat(sick.getEffectiveGramsPerUnit(outsideAt8)).isEqualTo(8.0);
}
@Test void byWindowMapCarriesEveryWindow() {
    assertThat(s.getEffectiveGramsPerUnitByWindow(LocalDateTime.of(2026, 11, 25, 8, 0)))
        .containsEntry(MealWindow.BREAKFAST, 8.0).containsEntry(MealWindow.LUNCH, 10.0)
        .containsEntry(MealWindow.DINNER, 12.0).containsEntry(MealWindow.NIGHT, 10.0);
}
@Test void noRatioAnywhereStaysNull() {
    UserSettingsDTO bare = new UserSettingsDTO();
    assertThat(bare.getWindowGramsPerUnit(MealWindow.LUNCH)).isNull();
    assertThat(bare.getEffectiveGramsPerUnit(LocalDateTime.of(2026, 11, 25, 12, 0))).isNull();
    assertThat(bare.getEffectiveGramsPerUnitByWindow(LocalDateTime.of(2026, 11, 25, 12, 0))).isEmpty();
}
@Test void windowRatiosAreSerialisedButTheMapIsNot() throws Exception {
    String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(s);
    assertThat(json).contains("\"gramsPerUnitBreakfast\":8.0").doesNotContain("effectiveGramsPerUnitByWindow");
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew test --tests 'che.glucosemonitorbe.dto.UserSettingsDTOWindowRatioTest'`. Expected: compilation FAIL (`getWindowGramsPerUnit` not found).

- [ ] **Step 3: Implement** the four DTO fields and methods above. Keep `UserSettingsDTOSickTest` green (its `gramsPerUnitScalesByTheSameFactor` has no window override, so it still reads the single ratio).

- [ ] **Step 4: Run** the DTO tests and `che.glucosemonitorbe.dto.UserSettingsDTOSickTest`. Expected: PASS.

- [ ] **Step 5: Write the failing service test** in `UserSettingsServiceTest`

```java
@Test void saveStoresWindowRatiosAndNullLeavesThemUnchanged() {
    // existing entity has gramsPerUnitLunch 9.0; DTO sends gramsPerUnitBreakfast 6.0, lunch null
    // -> saved entity: breakfast 6.0, lunch 9.0; returned DTO carries both
}
```

- [ ] **Step 6: Implement** schema + entity + service:
  - V1 `user_settings`: columns `grams_per_unit_breakfast`, `grams_per_unit_lunch`, `grams_per_unit_dinner`, `grams_per_unit_night` (`DOUBLE PRECISION`, nullable, comment "-- g/U override for 05:00-11:00; NULL = use grams_per_unit" etc.) and constraints `chk_user_settings_gpu_breakfast` … `chk_user_settings_gpu_night` = `CHECK (col IS NULL OR (col >= 3 AND col <= 30))`.
  - `upgrade-existing-db.sql`: `ADD COLUMN IF NOT EXISTS` for the four columns and a guarded `DO $$ … pg_constraint …` block per constraint, in the same style as `chk_user_settings_grams_per_unit`.
  - Entity: four `@Column` fields. Service: set each only when non-null in `updateSettings`; copy in `convertToDTO`; add them to both log lines.

- [ ] **Step 7: Extend `ExistingDbUpgradeIT.legacyDatabaseUpgradesAndBoots`**: assert the four columns exist (`information_schema.columns`) and the four constraints exist (`pg_constraint`).

- [ ] **Step 8: Run** `UserSettingsServiceTest`, `UserSettingsDTOWindowRatioTest`, `UserSettingsDTOSickTest`, then (Docker) `che.glucosemonitorbe.integration.ExistingDbUpgradeIT`. Expected: PASS.

- [ ] **Step 9: Commit** — `feat(settings): carb ratio per meal window`

---

### Task 2: Bolus calculator prices with the window ratio

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java` (`resolveGramsPerUnit`, ~line 263)
- Create: `src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceRatioWindowTest.java`

**Interfaces:**
- Consumes: `UserSettingsDTO.getWindowGramsPerUnit(LocalDateTime)`, `getEffectiveGramsPerUnit(LocalDateTime)` (Task 1)

- [ ] **Step 1: Write the failing tests** (setup like `InsulinCalculatorServiceIsfWindowTest`: isf 1.5 all windows, weight 84, single ratio 7.5, `gramsPerUnitBreakfast` 5.0; current = target so the correction is 0)

```java
@Test void breakfastIsPricedWithTheBreakfastRatio()   // 80 g at 08:00 -> 16.0 u
@Test void lunchFallsBackToTheSingleRatio()            // 80 g at 12:00 -> 10.67 u (within 0.01)
@Test void elevenOClockIsPricedAsLunch()               // 80 g at 11:00 -> 10.67 u
@Test void refusesAnOutOfRangeWindowRatio()            // breakfast 2.0 -> DosingRefusedException INSULIN_PARAMS_INCONSISTENT at 08:00; 12:00 still prices
@Test void windowRatioWorksWithoutASingleRatio()       // gramsPerUnit null, breakfast 5.0: 08:00 -> 16.0 u; 12:00 -> INSULIN_PARAMS_INCONSISTENT
```

- [ ] **Step 2: Run to verify failure**: `./gradlew test --tests 'che.glucosemonitorbe.service.InsulinCalculatorServiceRatioWindowTest'`. Expected: FAIL (breakfast priced at 10.67).
- [ ] **Step 3: Implement**: `resolveGramsPerUnit(settings, at)` validates `settings.getWindowGramsPerUnit(at)` against 3–30 (refusal message `"gramsPerUnit=" + value + " at=" + at`) and returns `settings.getEffectiveGramsPerUnit(at)`. Update its Javadoc.
- [ ] **Step 4: Run** the new test plus `InsulinCalculatorServiceTest`, `InsulinCalculatorServiceIsfWindowTest`, `InsulinCalculatorForecastCorrectionTest`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(dosing): price meals with the meal window's carb ratio`

---

### Task 3: Hovorka meal factor uses window ISF ÷ window ratio

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java` (`mealWindowFactor`, ~line 725-746, and its Javadoc)
- Modify: `src/test/java/che/glucosemonitorbe/hovorka/HovorkaMealWindowRatioTest.java`

**Interfaces:**
- Consumes: `UserSettingsDTO.getWindowGramsPerUnit(LocalDateTime)`, `HovorkaParameterService.usableGramsPerUnit(Double)`

- [ ] **Step 1: Write the failing tests** (the test's `settings(...)` helper gains a window-ratio argument that sets the override for the hour's window; base ISF 1.0, single ratio 10.0 as today)

```java
@ParameterizedTest(name = "{0}: ISF {2}, ratio {3}")
@CsvSource(nullValues = "none", value = {
        "breakfast, 8,  1.50, 6.0",
        "lunch,     12, 1.47, 12.0",
        "dinner,    18, none, 8.0",
        "night,     23, 1.88, none"})
void aMealPricedByItsWindowRatioLandsWhereItStarted(String w, int hour, Double isf, Double ratio)
    // units = CARBS / (ratio != null ? ratio : GRAMS_PER_UNIT); landing within 0.6 of START

@Test void aBreakfastMealIsSizedByIsfOverRatio()
    // breakfast ISF 1.5, ratio 6.0 -> factor = 1.5 * 10.0 / 6.0 = 2.5 (within 1e-9)
@Test void theFactorStaysClamped()
    // breakfast ISF 3.0, ratio 3.0 -> 10.0 -> clamped to MEAL_WINDOW_FACTOR_MAX (3.0)
@Test void anUnusableWindowRatioLeavesOnlyTheIsfPart()
    // lunch ISF 1.47, lunch ratio 2.0 (out of range) -> 1.47
```

Keep all existing tests in the class passing unchanged.

- [ ] **Step 2: Run to verify failure**: `./gradlew test --tests 'che.glucosemonitorbe.hovorka.HovorkaMealWindowRatioTest'`. Expected: FAIL on the new cases.
- [ ] **Step 3: Implement** `mealWindowFactor`: 1.0 when settings/time null or the single ratio is unusable (unchanged). Otherwise `isfPart` = window ISF ÷ `p.isf()` when both usable, else 1.0; `ratioPart` = single ratio ÷ window ratio when the window ratio is usable, else 1.0; return `isfPart × ratioPart` clamped to `[MEAL_WINDOW_FACTOR_MIN, MEAL_WINDOW_FACTOR_MAX]`. No sick multiplier, no twin scale.
- [ ] **Step 4: Run** the class. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(forecast): size each meal by its window's ISF and carb ratio`

---

### Task 4: Empirical twin uses a ratio per window

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/empirical/EmpiricalTwinParams.java`
- Modify: `src/main/java/che/glucosemonitorbe/empirical/EmpiricalTwinEngine.java:117`
- Modify: `src/main/java/che/glucosemonitorbe/empirical/EmpiricalEvent.java` (Javadoc of `localHour` only)
- Modify: `src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java` (`buildEmpiricalPath`, ~line 442)
- Modify: `src/test/java/che/glucosemonitorbe/empirical/EmpiricalTwinParamsTest.java`

**Interfaces:**
- Consumes: `UserSettingsDTO.getEffectiveGramsPerUnitByWindow(LocalDateTime)` (Task 1)
- Produces:
  - record components `crAM, crDay` replaced by `crBreakfast, crLunch, crDinner, crNight` (same position; `defaults()` = 7, 10, 10, 10)
  - `double crAt(double localHour)`: the window ratio for `MealWindow.fromHour((int) Math.floor(localHour))`, `crLunch` if empty
  - `static EmpiricalTwinParams forUser(Double isfMmolPerUnit, Double gramsPerUnit, Map<MealWindow, Double> windowGramsPerUnit, double diaHours, double peakMinutes)`: each window takes its map value if in 3–30, else `gramsPerUnit` if in 3–30, else that window's default. `csf` keeps today's rule with `gramsPerUnit` in place of `crDay` (`isf / gramsPerUnit` when usable, else `isf / 10`, else default).
  - the existing 4-arg `forUser(isf, gramsPerUnit, dia, peak)` delegates with `Map.of()`.

- [ ] **Step 1: Write the failing tests** in `EmpiricalTwinParamsTest`

```java
@Test void eachWindowUsesItsOwnRatio() {
    var p = EmpiricalTwinParams.forUser(3.0, 10.0,
            Map.of(MealWindow.BREAKFAST, 6.0, MealWindow.DINNER, 12.0), 4.0, 55.0);
    assertThat(p.crAt(8.5)).isEqualTo(6.0);
    assertThat(p.crAt(12.0)).isEqualTo(10.0);
    assertThat(p.crAt(18.0)).isEqualTo(12.0);
    assertThat(p.crAt(23.5)).isEqualTo(10.0);
}
@Test void withoutAnyRatioTheWindowDefaultsStay() {
    var p = EmpiricalTwinParams.forUser(null, null, Map.of(), 4.0, 55.0);
    assertThat(p.crAt(8.0)).isEqualTo(7.0);
    assertThat(p.crAt(13.0)).isEqualTo(10.0);
}
@Test void anImplausibleWindowRatioFallsBackToTheSingleRatio() {
    assertThat(EmpiricalTwinParams.forUser(3.0, 10.0, Map.of(MealWindow.LUNCH, 2.0), 4.0, 55.0).crAt(12.0))
            .isEqualTo(10.0);
}
@Test void aMealIsCountedAtItsWindowsRatio() {
    // 60 g MEAL at localHour 8.0, nothing else; rise after 240 min with breakfast ratio 6 is twice the rise with 12
    // (assert the ratio of the two rises is 2.0 within 0.01)
}
```

Update the existing tests: `dosingParametersComeFromTheUser` asserts `crAt(8)` and `crAt(13)` equal 15.0 instead of `crAM()`/`crDay()`; `aMissingOrImplausibleSettingKeepsItsDefault` uses `crAt(13)` for the old `crDay()` check.

- [ ] **Step 2: Run to verify failure**: `./gradlew test --tests 'che.glucosemonitorbe.empirical.EmpiricalTwinParamsTest'`. Expected: compilation FAIL.
- [ ] **Step 3: Implement** the record change, `crAt`, both `forUser` overloads; engine line 117 becomes `double cr = p.crAt(e.localHour());`; in `buildEmpiricalPath` call the 5-arg `forUser(userSettings.getEffectiveIsf(currentTime), userSettings.getEffectiveGramsPerUnit(currentTime), userSettings.getEffectiveGramsPerUnitByWindow(currentTime), rapidIob.diaHours(), rapidIob.peakMinutes())`. Update the `forUser` Javadoc ("settings carry no morning ratio" is no longer true).
- [ ] **Step 4: Run** `EmpiricalTwinParamsTest`, `EmpiricalTwinGoldenTest`, `GlucoseCalculationsServiceTest`. Expected: PASS (golden values unchanged: defaults keep 7/10).
- [ ] **Step 5: Commit** — `feat(empirical): carb ratio per meal window in the empirical twin`

---

### Task 5: ISF learner uses corrections only

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/IsfMealWindowProfileService.java`
- Modify: `src/main/java/che/glucosemonitorbe/domain/IsfMealWindowSnapshot.java` (Javadoc on `weightedSamples` only)
- Modify: `src/test/java/che/glucosemonitorbe/service/IsfMealWindowProfileServiceTest.java`

**Interfaces:**
- Produces (used by Task 6, same package):
  - `static Double nearestCgmMmol(List<CgmReading> readings, LocalDateTime target)` (was an instance method; body unchanged)
  - `static CarbsEntry buildCarbsEntry(Note n)` (was private instance; body unchanged)
  - `estimateForBolus(...)` overloads lose their `Double gramsPerUnit` parameter; the ratio comes from `userSettings.getWindowGramsPerUnit(t0)` (null settings → no estimate)
- Removes: `WEIGHT_MEAL`

- [ ] **Step 1: Change the tests first**
  - Replace `mealBolus_weightedLower_andCarbsDeconvolved` with `mealBolus_givesNoIsfEstimate`: 4 U + 40 g at 13:00 → LUNCH snapshot has `rawSampleCount` 0 and `isfMmolPerU` null.
  - Add `correctionCarbsAreDeconvolvedWithTheWindowRatio`: 2 U + 5 g carbs at 07:30, settings single ratio 12.5 and `gramsPerUnitBreakfast` 5.0, CGM 9.0 → 4.0 over DIA → BREAKFAST ISF ≈ 5.0 (offset 0.05). (With 12.5 it would be ≈ 3.1.)
  - Drop the `12.5` argument from every direct `estimateForBolus(...)` call (`USER_SETTINGS` already carries 12.5).
  - Update the class Javadoc bullet about meal weighting.

- [ ] **Step 2: Run to verify failure**: `./gradlew test --tests 'che.glucosemonitorbe.service.IsfMealWindowProfileServiceTest'`. Expected: compilation FAIL (old signature), then the two new tests FAIL.
- [ ] **Step 3: Implement**: after computing `carbsGrams + priorCobAtStart`, return `null` when it is `>= CARB_THRESHOLD_GRAMS`; every surviving estimate has weight `WEIGHT_CORRECTION`. Use `userSettings.getWindowGramsPerUnit(t0)` for the ratio (same 3–30 check). Make the two helpers static as listed. Update the class Javadoc algorithm (step 3–4) and the snapshot Javadoc ("every contributing event weighs 1.0").
- [ ] **Step 4: Run** the class and `IsfMealWindowSuggestionServiceTest`. Expected: PASS.
- [ ] **Step 5: Commit** — `feat(isf): learn meal-window ISF from corrections only`

---

### Task 6: Ratio learner, snapshot table, recompute wiring

**Files:**
- Create: `src/main/resources/db/migration/V23__carb_ratio_meal_window_snapshots.sql`
- Create: `src/main/java/che/glucosemonitorbe/domain/CarbRatioMealWindowSnapshot.java`
- Create: `src/main/java/che/glucosemonitorbe/repository/CarbRatioMealWindowSnapshotRepository.java`
- Create: `src/main/java/che/glucosemonitorbe/dto/CarbRatioWindowEstimate.java`
- Create: `src/main/java/che/glucosemonitorbe/service/CarbRatioMealWindowProfileService.java`
- Modify: `src/main/java/che/glucosemonitorbe/scheduler/IsfMealWindowScheduler.java`
- Modify: `src/main/java/che/glucosemonitorbe/controller/IsfMealWindowController.java` (`recompute`)
- Create: `src/test/java/che/glucosemonitorbe/service/CarbRatioMealWindowProfileServiceTest.java`

**Interfaces:**
- Consumes: `IsfMealWindowProfileService.nearestCgmMmol`, `.buildCarbsEntry`, `.noteTimestampToEpochMs` (Task 5); `UserSettingsDTO.getWindowIsf(LocalDateTime)`; `SickProfile.excludedFromLearning`; `InsulinCalculatorService.iobOpenApsExponential`; `CarbsOnBoardService.calculateTotalCarbsOnBoard(List<CarbsEntry>, LocalDateTime, UserSettingsDTO)`; `UserInsulinPreferencesService.getRapidIobParameters`, `.rapidCurveByCode`.
- Produces:
  - table `carb_ratio_meal_window_snapshots(id uuid pk default gen_random_uuid(), user_id uuid not null fk users on delete cascade, meal_window varchar(20) not null check in the four names, grams_per_unit double precision null check 3–30, sample_count integer not null default 0, computed_at timestamptz not null default now(), unique(user_id, meal_window))` + index on `user_id`
  - entity `CarbRatioMealWindowSnapshot` (`id`, `userId`, `MealWindow mealWindow` as STRING, `Double gramsPerUnit`, `Integer sampleCount`, `LocalDateTime computedAt` set in `@PrePersist/@PreUpdate`), Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor` like `IsfMealWindowSnapshot`
  - repository: `List<CarbRatioMealWindowSnapshot> findByUserId(UUID)`, `Optional<CarbRatioMealWindowSnapshot> findByUserIdAndMealWindow(UUID, MealWindow)`
  - `public record CarbRatioWindowEstimate(MealWindow window, Double gramsPerUnit, int sampleCount)` (`gramsPerUnit` null = no data)
  - `CarbRatioMealWindowProfileService`:
    - constants `HISTORY_DAYS = 14`, `MIN_MEALS = 3`, `MEAL_CARB_THRESHOLD_GRAMS = 10.0`, `ANCHOR_TOLERANCE_MINUTES = 30`, `PRIOR_CARB_LOOKBACK_HOURS = 8`, `MIN_UNITS_NEEDED = 0.5`, `MIN_GRAMS_PER_UNIT = 3.0`, `MAX_GRAMS_PER_UNIT = 30.0`
    - `@Transactional(readOnly = true) List<CarbRatioWindowEstimate> getProfile(UUID userId)`: always 4 entries in `MealWindow.values()` order, missing rows as `(window, null, 0)`
    - `@Transactional List<CarbRatioWindowEstimate> recomputeForUser(UUID userId)`: recompute, upsert one row per window, return `getProfile`

- [ ] **Step 1: Write the failing tests** (mock setup copied from `IsfMealWindowProfileServiceTest`: DIA 4.5 h / peak 75, settings ISF 2.5, single ratio 10.0, COB mock returns 0.0, snapshot save returns its argument; `today(h, m)`, `bolus`, `bolusWithCarbs`, `cgmReading` helpers copied; add `meal(ts, carbs)` = carbs, no insulin, and `rescue(ts, carbs)` = type `Note.TYPE_HYPO_TREATMENT`). Unless stated, each scenario is repeated on 3 consecutive days so `MIN_MEALS` is met; CGM readings sit at `t0` (the earliest bolus of the meal) and `t0 + 4.5 h`, and "flat" means both read 7.0; Δ is CGM(tEnd) − CGM(t0).

```java
@Test void cleanMeal_ratioIsCarbsOverUnitsNeeded()          // 60 g + 6 U at 08:00, 7.0 -> 8.5: BREAKFAST 9.09 (offset 0.05), sampleCount 3
@Test void fewerThanThreeMeals_noData()                       // 2 days only: gramsPerUnit null, sampleCount 2
@Test void medianNotMean()                                    // 48 g, 54 g, 120 g each with 6 U, flat CGM -> 9.0
@Test void followUpCorrectionIsCounted()                      // 60 g + 5 U at 08:00, 1 U at 10:00, flat CGM:
                                                              //   expected 60 / (5 + 1 - iobOpenApsExponential(1, 150, 4.5, 75))
@Test void rescueCarbsAreCounted()                            // 60 g + 8 U at 08:00, rescue 15 g at 10:30, flat -> 75 / 8 = 9.375
@Test void splitBolusIsOneMeal()                              // 60 g at 08:00, 3 U at 07:50 and 3 U at 08:10, flat:
                                                              //   one event per day (sampleCount 3), ratio within 0.2 of 10.0
@Test void preBolusLoggedSeparatelyAnchorsTheMeal()           // 6 U at 07:40 (no carbs), meal 60 g at 08:00, CGM taken at 07:40 and 07:40+DIA, flat -> 10.0
@Test void secondMealInsideTheWindowSkipsTheFirst()           // 60 g + 6 U at 08:00 and 40 g + 4 U at 11:00 daily:
                                                              //   BREAKFAST sampleCount 0; LUNCH sampleCount 3
@Test void mealAfterMidnightBucketsIntoNight()                // 30 g + 3 U at 00:30, flat -> NIGHT 10.0
@Test void unbolusedMeal_ignored()                            // 60 g, no insulin within 30 min -> no samples anywhere
@Test void sickPeriodSkipped()                                // as cleanMeal but settings carry a sick span covering the days -> sampleCount 0
@Test void missingCgmSkipped()                                // no reading near tEnd -> sampleCount 0
@Test void outOfRangeRatioDropped()                           // 60 g + 1.5 U, flat -> 40 g/U -> dropped
@Test void unitsNeededBelowHalfUnitDropped()                  // 30 g + 1 U, CGM 9.0 -> 7.5 at ISF 2.5 -> 0.4 U -> dropped
@Test void longActingIgnored()                                // a long-acting note with carbs does not anchor
@Test void getProfile_fillsMissingWindows()                   // repo returns LUNCH only -> 4 entries, others (null, 0)
```

- [ ] **Step 2: Run to verify failure**: `./gradlew test --tests 'che.glucosemonitorbe.service.CarbRatioMealWindowProfileServiceTest'`. Expected: compilation FAIL.

- [ ] **Step 3: Implement migration, entity, repository, record.**

- [ ] **Step 4: Implement the learner.** Data access and time handling exactly as `IsfMealWindowProfileService.recomputeForUser` (notes in `[now − 14 d, now]` on the JVM zone axis, CGM since the same bound). Event algorithm:

```
meals   = notes with carbs >= 10, not hypo treatment, not long-acting, timestamp != null; sorted by time
rapid   = notes with insulin > 0, not long-acting, timestamp != null
usedT0  = {}
for m in meals:
    boluses = rapid with |t - m.t| <= 30 min
    if boluses empty: continue                            // unbolused meal
    t0 = min(bolus times); if t0 in usedT0: continue     // same meal logged twice
    if settings.sickProfile.excludedFromLearning(t0): continue
    tEnd = t0 + DIA(current rapid insulin)
    if any meal m2 with m2.t in (t0 + 30 min, tEnd]: continue
    g0 = nearestCgmMmol(cgm, t0); g1 = nearestCgmMmol(cgm, tEnd); if either null: continue
    isfW = settings.getWindowIsf(t0); if null or <= 0: continue
    window entries = carb notes (incl. rescue) with t in [t0 - 30 min, tEnd]
    prior entries  = carb notes with t in [t0 - 8 h, t0 - 30 min)
    carbs = sum(window grams) + COB(prior, t0) - COB(window + prior, tEnd)
    insulin = sum over rapid d:
                d.t <  t0          -> IOB_d(t0)            (its own curve via rapidCurveByCode)
                d.t == t0          -> d.units
                t0 < d.t <= tEnd   -> d.units - IOB_d(tEnd)
    needed = insulin + (g1 - g0) / isfW; if needed < 0.5: continue
    ratio = carbs / needed; if ratio outside [3, 30]: continue
    usedT0.add(t0); bucket ratio under MealWindow.fromTimestamp(t0)
per window: gramsPerUnit = count >= 3 ? median : null   (even count: mean of the two middle values)
```

Log one summary line per recompute (candidates, events per window, skip counts), like the ISF learner.

- [ ] **Step 5: Run** the learner tests. Expected: PASS.
- [ ] **Step 6: Wire recompute**: `IsfMealWindowScheduler.recomputeAllUsers` calls `carbRatioProfileService.recomputeForUser(user.getId())` after the ISF recompute, inside the same try (fix the Javadoc: there is no on-bolus refresh). `IsfMealWindowController.recompute` also calls it (response body unchanged).
- [ ] **Step 7: Run** `./gradlew compileJava`. Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 8: Update `ExistingDbUpgradeIT`** for V23: expected max Flyway version 22 → 23, the "V13-V22" wording → "V13-V23", and assert `to_regclass('carb_ratio_meal_window_snapshots') IS NOT NULL`. Run it (Docker): `./gradlew test --tests 'che.glucosemonitorbe.integration.ExistingDbUpgradeIT'`. Expected: PASS.
- [ ] **Step 9: Commit** — `feat(isf): learn carb ratio per meal window from clean meals`

---

### Task 7: Suggestion card carries ratio lines; accept needs the client's opt-in

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/dto/IsfMealWindowSuggestionDTO.java`
- Create: `src/main/java/che/glucosemonitorbe/dto/AcceptSuggestionRequest.java`
- Modify: `src/main/java/che/glucosemonitorbe/service/IsfMealWindowSuggestionService.java`
- Modify: `src/main/java/che/glucosemonitorbe/controller/IsfMealWindowController.java` (`acceptSuggestion`)
- Modify: `src/test/java/che/glucosemonitorbe/service/IsfMealWindowSuggestionServiceTest.java`
- Create: `src/test/java/che/glucosemonitorbe/controller/IsfMealWindowControllerTest.java`

**Interfaces:**
- Consumes: `CarbRatioMealWindowProfileService.getProfile(UUID)` → `List<CarbRatioWindowEstimate>` (Task 6); `UserSettingsDTO.getWindowGramsPerUnit(MealWindow)` (Task 1)
- Produces:
  - `WindowProposal` gains `Double currentGramsPerUnit`, `Double proposedGramsPerUnit`, `boolean ratioHasData`, `Integer ratioSamples`
  - `public record AcceptSuggestionRequest(Boolean includeRatios)`
  - `IsfMealWindowSuggestionService.accept(UUID userId, boolean includeRatios)` replaces `accept(UUID)`; constructor gains `CarbRatioMealWindowProfileService`
  - constants `RATIO_STEP_CAP = 0.20`, `MIN_RATIO_CHANGE = 0.10`

Rules (from the spec): current ratio = `getWindowGramsPerUnit(window)`. When learned is non-null: if current is in 3–30, proposed = learned clamped to `[current × 0.8, current × 1.2]`; otherwise proposed = learned; round to 0.1. A ratio line is material when current is unusable or `|proposed − current| >= 0.10 × current`. ISF lines: when the twin is not ready, `proposedIsf` = null and `hasData` = false. `show` = cadence ok AND (ISF qualifies by today's rules OR any ratio line is material). When neither qualifies, `suppressReason` keeps today's order (`twin_not_ready`, `insufficient_window_data`, `no_material_change`); `cadence` when only the cadence blocks. Accept: apply ISF proposals only when the twin is ready; apply ratio proposals (`ratioHasData && proposedGramsPerUnit != null`) only when `includeRatios`; throw `IllegalStateException("Nothing to apply")` if nothing was applied; otherwise save once and record `lastAcceptedAt`.

- [ ] **Step 1: Write the failing service tests** (`setUp` stubs the ratio profile leniently with four no-data estimates; `stubRatios(...)` overrides it; `stubSettings` gains a single-ratio argument, 10.0)

```java
@Test void ratioLine_cappedAtTwentyPercent()                       // current 10, learned 6.0 -> proposed 8.0
@Test void ratioLine_roundedToOneDecimal_andSmallChangeNotMaterial()// learned 9.27 -> 9.3; twin not ready -> show false, "twin_not_ready"
@Test void ratioLine_usesTheWindowOverrideAsCurrent()              // breakfast override 8.0, learned 7.0 -> current 8.0, proposed 7.0
@Test void ratioLine_withoutCurrentRatio_isUncappedAndMaterial()   // single ratio null, learned 14.0 -> proposed 14.0, show true
@Test void show_whenOnlyARatioLineIsMaterial_evenIfTwinNotReady()  // twin empty, learned 8.0 vs 10 -> show true; every proposedIsf null
@Test void accept_withRatios_appliesIsfAndRatios()                 // twin ready: isfBreakfast 2.8 and gramsPerUnitBreakfast 8.0 saved
@Test void accept_withoutRatios_appliesIsfOnly()                   // same data, includeRatios false: gramsPerUnitBreakfast null in the patch
@Test void accept_twinNotReady_withRatios_appliesRatiosOnly()      // isf overrides in the patch equal the stored ones; ratio applied
@Test void accept_nothingToApply_throws()                          // twin not ready, includeRatios false -> IllegalStateException
```

Update the existing tests for the new constructor and `accept(userId, false)`; they must keep passing as written otherwise.

- [ ] **Step 2: Run to verify failure**: `./gradlew test --tests 'che.glucosemonitorbe.service.IsfMealWindowSuggestionServiceTest'`. Expected: compilation FAIL.
- [ ] **Step 3: Implement** the DTO fields, record, and service rules above.
- [ ] **Step 4: Write the controller tests** (plain Mockito, call the method directly; `UserService.getUserByUsername(name).getId()` returns a fixed id)

```java
@Test void acceptWithoutBodyAppliesIsfOnly()        // body null -> verify accept(id, false)
@Test void acceptWithIncludeRatiosPassesTrue()      // new AcceptSuggestionRequest(true) -> verify accept(id, true)
@Test void nothingToApplyIsAConflict()              // service throws IllegalStateException -> ResponseStatusException 409
```

- [ ] **Step 5: Implement** `acceptSuggestion(Authentication auth, @RequestBody(required = false) AcceptSuggestionRequest body)`: `includeRatios = body != null && Boolean.TRUE.equals(body.includeRatios())`. Update the `@Operation` summaries to mention carb ratios.
- [ ] **Step 6: Run** both test classes. Expected: PASS.
- [ ] **Step 7: Commit** — `feat(suggestion): propose per-window carb ratios on the morning card`

---

### Task 8: iPhone settings grouped by meal (ISF + carb ratio per window)

**Files (iPhone worktree):**
- Modify: `GlucoseMonitor/BackendAPI.swift` (`COBSettings`, ~line 222-247)
- Modify: `GlucoseMonitor/SettingsView.swift` (rows after "Insulin-to-carb ratio", bindings near `gramsPerUnitBinding`)
- Modify: `GlucoseMonitorTests/BackendAPIHovorkaTests.swift`

**Interfaces:**
- Produces: `COBSettings` gains `var gramsPerUnitBreakfast: Double? = nil`, `gramsPerUnitLunch`, `gramsPerUnitDinner`, `gramsPerUnitNight` — appended **after** `gramsPerUnit` so existing memberwise calls compile.

- [ ] **Step 1: Write the failing tests** in `BackendAPIHovorkaTests`

```swift
func testCOBSettings_roundTrip_preservesWindowRatios()   // breakfast 6, night 12 survive encode -> decode
func testCOBSettings_nilWindowRatiosAreOmitted()         // encoded JSON has no "gramsPerUnitLunch" key when nil
```

- [ ] **Step 2: Run to verify failure** with the iPhone test template, `-only-testing:GlucoseMonitorTests/BackendAPIHovorkaTests`. Expected: build FAIL (unknown argument).
- [ ] **Step 3: Implement** the four fields (doc comments like the ISF ones: "Carb ratio override for 05:00-11:00 (grams per unit). Nil = use `gramsPerUnit`."). In `SettingsView`, group by meal as sketched in spec section 6:
  - "User Settings" section keeps Glucose Units, "Insulin-to-carb ratio" (subtitle `"grams per unit * default"`), Body Weight, Target Glucose (+ error), Rescue Carbs (+ error). The four "ISF Breakfast…Night" rows move out of it.
  - Four sections with headers `"Breakfast · 05:00 – 11:00"`, `"Lunch · 11:00 – 16:00"`, `"Dinner · 16:00 – 22:00"`, `"Night · 22:00 – 05:00"`. Each holds two `SettingsNumericRow`s: "ISF" (subtitle `"mmol/L per unit"`, placeholder `"2.5"`, the existing `isf<Window>Binding`) and "Carb ratio" (subtitle `"grams per unit"`, placeholder `"5"`, `fractionDigits: 1...2`, a new binding whose getter is `cobSettings.gramsPerUnit<Window> ?? cobSettings.gramsPerUnit ?? 5` and whose setter stores the typed value).
  - A final section after the meal sections holds Save (same disabled rule), the status text, the Insulin Preferences link and Sign Out.
  - All of it stays inside `if appState.isAuthenticated`.
- [ ] **Step 4: Run** the tests. Expected: `** TEST SUCCEEDED **`.
- [ ] **Step 5: Commit** — `feat(settings): group ISF and carb ratio by meal`

---

### Task 9: iPhone suggestion card shows ratios and opts in on Apply

**Files (iPhone worktree):**
- Modify: `GlucoseMonitor/BackendAPI.swift` (`IsfMealWindowSuggestion`, `acceptIsfMealWindowSuggestion`)
- Modify: `GlucoseMonitor/ContentView.swift` (`shouldShowIsfBanner`, `isfSuggestionBanner`, `acceptIsfSuggestion`)
- Modify: `GlucoseMonitorTests/BackendAPIHovorkaTests.swift`

**Interfaces:**
- Consumes: backend `WindowProposal` JSON fields `currentGramsPerUnit`, `proposedGramsPerUnit`, `ratioHasData`, `ratioSamples` (Task 7)
- Produces:
  - `WindowProposal` gains `let currentGramsPerUnit: Double?`, `let proposedGramsPerUnit: Double?`, `let ratioHasData: Bool?`, `let ratioSamples: Int?`; computed `hasIsfProposal` (`hasData && proposedIsf != nil`) and `hasRatioProposal` (`ratioHasData == true && proposedGramsPerUnit != nil`)
  - `proposalLines` = windows where `hasIsfProposal || hasRatioProposal`; `allWindowsOrdered` padding passes nil for the new fields
  - `struct AcceptSuggestionBody: Encodable { let includeRatios: Bool }` inside `BackendAPI`
  - `static func acceptIsfMealWindowSuggestion(includeRatios: Bool = true) async throws` sends that body

- [ ] **Step 1: Write the failing tests**

```swift
func testSuggestion_decodesRatioFields()               // JSON with ratio fields -> proposedGramsPerUnit 8.0, hasRatioProposal true
func testSuggestion_withoutRatioFields_stillDecodes()  // today's JSON -> hasRatioProposal false, proposalLines = ISF windows
func testSuggestion_ratioOnlyWindowIsAProposalLine()   // hasData false, ratio proposal present -> in proposalLines
func testAcceptSuggestionBody_encodesIncludeRatios()   // encodes to {"includeRatios":true}
```

- [ ] **Step 2: Run to verify failure** (iPhone template, `BackendAPIHovorkaTests`). Expected: build FAIL.
- [ ] **Step 3: Implement** the model and request changes. Banner: title `"Updated settings ready"`; caption `"Based on your recent data, we suggest refining ISF and carb ratios by time of day. Review and apply, or dismiss for a few days."`; each window row shows the name, then the ISF text (`"%.2f → %.2f"` when `hasIsfProposal`, `"%.2f"` when only `currentIsf`), then the ratio text (`"%.1f → %.1f g/U"` when `hasRatioProposal`, `"%.1f g/U"` when only `currentGramsPerUnit`); `"No data yet"` when the window has neither proposal. `shouldShowIsfBanner` keeps its logic (now driven by the wider `proposalLines`). Apply calls `acceptIsfMealWindowSuggestion(includeRatios: true)`; the error text becomes `"Could not apply suggestion."`.
- [ ] **Step 4: Run** the tests, then a simulator build (`xcodebuild build …` same destination). Expected: `** TEST SUCCEEDED **`, `** BUILD SUCCEEDED **`.
- [ ] **Step 5: Commit** — `feat(suggestion): show carb ratio proposals on the morning card`

---

### Task 10: End-to-end forecast check

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/integration/CarbRatioWindowE2ETest.java`

- [ ] **Step 1: Write the test with the `prediction-e2e-tests` skill** (it owns the setup for `POST /api/glucose-calculations/`). Test `breakfastDosedByBreakfastRatioForecastsFlat`: settings base ISF 1.0, single ratio 10, `isfBreakfast` 1.5, `gramsPerUnitBreakfast` 6.0; at 08:00 log 60 g and 10 U (= 60 / 6), starting glucose 7.0 → the 4 h prediction lands within 1.0 mmol/L of 7.0. Companion `breakfastDosedByTheSingleRatioForecastsHigh`: same but 6 U (= 60 / 10) → lands at least 2.0 mmol/L above 7.0.
- [ ] **Step 2: Run** it. Expected: PASS (if the first fails, the bug is in Task 3, not here).
- [ ] **Step 3: Commit** — `test(e2e): breakfast dosed by its window ratio forecasts flat`

---

### Task 11: Full verification and dev database

- [ ] **Step 1: Full backend suite**: `./gradlew test` (Docker running). Expected: `BUILD SUCCESSFUL`. Report any failure with its output; do not mark done.
- [ ] **Step 2: Full iPhone tests** (template without `-only-testing`). Expected: only the known pre-existing failures listed in Global Constraints.
- [ ] **Step 3: Simulator look**: run the app in the iOS Simulator, open Settings, screenshot the four meal sections (ISF + carb ratio in each) and check Save sits below them.
- [ ] **Step 4: Dev database — only with the user's go-ahead**: back up, run `scripts/upgrade-existing-db.sql` against the dev DB, `flyway repair` (V1 checksum changed), restart the backend, `GET` the user settings and confirm the four `gramsPerUnit*` keys are present, then `POST /api/isf/meal-windows/recompute` and check the log line of the ratio learner.
- [ ] **Step 5: Hand off** with superpowers:finishing-a-development-branch (two branches, one per repo; the backend must deploy before or with the app build, though an older app stays safe either way).
