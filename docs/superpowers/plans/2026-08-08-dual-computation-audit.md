# Dual-Computation Divergence Audit — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a findings report listing every place in `glucose-monitor-be`'s
prediction/calculation core where a logical quantity is computed via more than one
independent code path with nothing enforcing agreement — the pattern behind the
2h-forecast bug fixed in `30b76bd` — plus a concrete pass/fail test for the specific
"jump at now" symptom observed under active IOB/COB.

**Architecture:** No new production code paths. Five independent tracing tasks each read
specific methods/call sites and append one entry to a shared findings-report markdown file;
one task adds a real JUnit test reproducing the observed chart-jump scenario. A final task
compiles and cross-checks the report against the design spec's target list.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, AssertJ (existing test stack — no new
dependencies).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-08-dual-computation-audit-design.md`
- Backend only (`glucose-monitor-be`) — do not touch iOS or web frontend repos.
- Pattern only: a quantity computed by >1 independent path. Do not report unrelated bug
  classes (stale caches, unit conversion, timezone handling) even if noticed in passing —
  note them in the report's "Observed but out of scope" section instead.
- No production code changes in this plan. The one test task (Task 5) adds a test only; if it
  fails, that failure IS the finding — do not fix the underlying code as part of this plan.
- Handling uncertainty (spec §4): if you find a genuine divergence but can't tell from the
  code, comments, or git history whether it's a bug or an intentional simplification, do not
  guess either way. Stop that task, write the divergence up as `Status: needs user input` in
  the report (quantity + both paths + why intentionality is unclear), and move on to the next
  task rather than blocking on it — the user resolves these when reviewing the compiled report
  in Task 6.
- Findings report lives at `docs/superpowers/specs/2026-08-08-dual-computation-findings.md`,
  created in Task 1 and appended to by Tasks 2–5. Every task that appends must `git add` +
  commit just that file (plus its own test file where applicable) — never batch commits
  across tasks.
- Report entry format (use exactly this shape for every finding):

  ```markdown
  ## Finding: <short name>

  **Quantity:** <what logical value this is about>
  **Paths:**
  - `path/File.java:LINE` — <method name>, <one-line description of how it computes the value>
  - `path/File.java:LINE` — <method name>, <one-line description of how it computes the value>
  **Disagreement scenario:** <concrete inputs where the two paths would produce different
  numbers, with rough values if you can compute them>
  **Confidence:** confirmed-by-reading | suspected-needs-verification
  **Status:** open | not-a-bug (intentional — explain why) | needs user input (explain what's
  ambiguous)
  ```

  For a target traced and found clean, append instead:

  ```markdown
  ## Traced, no divergence found: <short name>

  <One paragraph: what you checked and why it's a single source of truth.>
  ```

---

### Task 1: Findings report skeleton + Finding: ISF Fallback Divergence

**Files:**
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings.md`

**Interfaces:**
- Consumes: nothing (first task)
- Produces: the findings report file, which Tasks 2–5 append to. Every later task must
  `git pull`/re-read this file immediately before appending, since multiple tasks touch it.

This finding is already confirmed by reading the code this session — the task is to verify
the citations are still accurate (line numbers can drift) and write it up.

- [ ] **Step 1: Verify the two `resolveIsf` implementations still look like this**

  Run:
  ```bash
  grep -n "private double resolveIsf" glucose-monitor-be/src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java glucose-monitor-be/src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java
  ```
  Expected output (line numbers may have shifted slightly — use whatever grep reports):
  ```
  .../service/GlucoseCalculationsService.java:241:    private double resolveIsf(UserSettingsDTO userSettings, LocalDateTime time) {
  .../hovorka/HovorkaGlucosePredictionService.java:506:    private double resolveIsf(UserSettingsDTO settings, double fallbackIsf, LocalDateTime time) {
  ```
  Read both method bodies (a few lines below each match). Confirm:
  - `GlucoseCalculationsService.resolveIsf` returns `DEFAULT_ISF` (a hardcoded constant, `1.0`
    mmol/L per unit, declared near the top of the class) when no per-meal-window manual
    override applies.
  - `HovorkaGlucosePredictionService.resolveIsf` returns a caller-supplied `fallbackIsf`
    instead — and its only caller passes `pAdj.isf()`, the user's autotuned/calibrated ISF
    from `HovorkaParameterService`, not `1.0`.

  If either method's fallback behavior has changed from this description, stop and note the
  actual current behavior in the finding instead of what's written below.

- [ ] **Step 2: Confirm which response field is affected**

  Run:
  ```bash
  grep -n "insulinContribution\|determineTrend(\|predictionTrend" glucose-monitor-be/src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java
  ```
  Confirm `calculatePredictionFactors` (which calls the `DEFAULT_ISF`-fallback `resolveIsf`)
  computes `factors.insulinContribution`, and `determineTrend(factors, ...)` (called at
  ~line 135 in `calculateGlucoseData`) uses that same `factors` object to produce the
  `predictionTrend` string ("rising"/"falling"/"stable") that ships in the response — this
  field was NOT touched by the `30b76bd` fix, which only changed `twoHourPrediction`.

- [ ] **Step 3: Write the finding**

  Append to `docs/superpowers/specs/2026-08-08-dual-computation-findings.md` (create the file
  with a one-line `# Dual-Computation Divergence Audit — Findings` header first if it doesn't
  exist yet):

  ```markdown
  ## Finding: ISF Fallback Divergence (affects predictionTrend)

  **Quantity:** the insulin sensitivity factor (ISF) used to price insulin's glucose-lowering
  effect for a given time window, when no manual per-meal-window override is set.

  **Paths:**
  - `service/GlucoseCalculationsService.java:241` — `resolveIsf(settings, time)`, falls back
    to hardcoded `DEFAULT_ISF = 1.0` mmol/L/U when no override applies. Feeds
    `calculatePredictionFactors` -> `factors.insulinContribution` -> `determineTrend` ->
    the `predictionTrend` field ("rising"/"falling"/"stable") still shipped in every response.
  - `hovorka/HovorkaGlucosePredictionService.java:506` — `resolveIsf(settings, fallbackIsf,
    time)`, falls back to the caller-supplied `fallbackIsf`, which is always `pAdj.isf()` —
    the user's autotuned/calibrated ISF from `HovorkaParameterService`. Feeds the actual
    `predictionPath` array (the chart).

  **Disagreement scenario:** a user with autotuned ISF far from 1.0 (per project memory,
  the 1800-rule ISF commonly runs ~2x the model's calibrated ISF, so calibrated values well
  above or below 1.0 are the norm, not the exception) and no manual per-meal-window override
  set. The chart (Hovorka path) prices every unit of insulin at their real ISF; the
  `predictionTrend` label is computed as if ISF were 1.0. For a user with e.g. calibrated
  ISF=3.0, a 2u dose is priced as -6.0 mmol/L of effect on the chart but only -2.0 mmol/L in
  the trend formula — the trend label can say "stable" or even "rising" while the chart the
  label sits next to is falling sharply, or vice versa.

  **Confidence:** confirmed-by-reading.
  **Status:** open.
  ```

- [ ] **Step 4: Commit**

  ```bash
  cd glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings.md
  git commit -m "docs: add ISF fallback divergence finding (dual-computation audit)"
  ```

---

### Task 2: Trace `predictionTrend` shape-consistency with `predictionPath`

**Files:**
- Modify: `docs/superpowers/specs/2026-08-08-dual-computation-findings.md`

**Interfaces:**
- Consumes: Finding from Task 1 (read it first — this task checks whether there's a second,
  independent way `predictionTrend` can disagree with the chart, beyond the ISF fallback).
- Produces: one more entry (or a "traced, no divergence" note) appended to the same report.

- [ ] **Step 1: Read `determineTrend` in full**

  Run:
  ```bash
  grep -n "private String determineTrend" -A 20 glucose-monitor-be/src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java
  ```
  Note the threshold constants it compares `netEffect` against (`TREND_RISING_THRESHOLD`,
  `TREND_FALLING_THRESHOLD` — find their values with
  `grep -n "TREND_RISING_THRESHOLD\|TREND_FALLING_THRESHOLD" glucose-monitor-be/src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java`).

- [ ] **Step 2: Check whether `netEffect`'s horizon matches the trend label's implied horizon**

  `determineTrend` is called with `predictionHorizon` (default 120 min, i.e. matches the 2h
  mark). Check whether the label is meant to describe the 2h trend specifically, or the
  general shape of the whole 4h `predictionPath`. Read any comment/doc on the `predictionTrend`
  field in `dto/GlucoseCalculationsResponse.java` (`grep -n "predictionTrend" glucose-monitor-be/src/main/java/che/glucosemonitorbe/dto/GlucoseCalculationsResponse.java`)
  for the intended meaning.

  If the field is documented/intended as "trend over the full horizon" but computed from a
  single-horizon `netEffect` snapshot, that's a second, independent divergence source (not
  just the ISF fallback) — write it up as its own finding using the template. If it's
  documented/intended as "trend at the 2h mark" and `netEffect` is in fact a 2h-horizon
  calculation, this is consistent by design — write the "traced, no divergence found" note
  instead, but cross-reference Finding 1 since the ISF issue still applies at whatever horizon
  it's computed for.

- [ ] **Step 3: Append the result to the findings report, then commit**

  ```bash
  cd glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings.md
  git commit -m "docs: trace predictionTrend horizon consistency (dual-computation audit)"
  ```

---

### Task 3: Trace `confidence` and re-verify the COB/IOB single-source claim

**Files:**
- Modify: `docs/superpowers/specs/2026-08-08-dual-computation-findings.md`

**Interfaces:**
- Consumes: nothing new.
- Produces: one or two "traced, no divergence found" notes (or findings, if the checks below
  turn up something unexpected) appended to the report.

`calculateConfidence` (line ~482) is a self-contained heuristic with exactly one call site
(`calculateGlucoseData` line ~135) — a quick check should confirm there's no second computation
of "confidence" anywhere else in the service layer.

- [ ] **Step 1: Confirm `calculateConfidence` has a single call site**

  Run:
  ```bash
  grep -rn "calculateConfidence(" glucose-monitor-be/src/main/java/
  ```
  Expected: exactly two matches — the method declaration and its one call site, both in
  `GlucoseCalculationsService.java`. If there's a second call site or a second method with
  similar purpose (e.g. in `ExperimentService`), write it up as a finding instead of the "no
  divergence" note.

- [ ] **Step 2: Re-verify COB/IOB single-source-of-truth still holds**

  Project memory states `GlucoseCalculationsService.activeCobIobInputs` is the single source
  for COB/IOB, shared by the dashboard and `ExperimentService.checkBackground`. Confirm this
  is still true and hasn't regressed:
  ```bash
  grep -n "activeCobIobInputs" glucose-monitor-be/src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java glucose-monitor-be/src/main/java/che/glucosemonitorbe/service/ExperimentService.java
  ```
  Confirm `ExperimentService` calls `activeCobIobInputs(...)` (or a method that delegates to
  it) rather than recomputing COB/IOB independently via its own calls to `CarbsOnBoardService`
  / `InsulinCalculatorService`. If you find `ExperimentService` (or anything else) calling
  `cobService.calculateTotalCarbsOnBoard` or `insulinCalculatorService.calculateTotalActiveInsulin`
  directly, outside of `activeCobIobInputs` and `GlucoseCalculationsService`'s own prediction
  loop, write that up as a finding — that's a second independent COB/IOB computation.

- [ ] **Step 3: Append results to the findings report, then commit**

  ```bash
  cd glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings.md
  git commit -m "docs: trace confidence + re-verify COB/IOB single source (dual-computation audit)"
  ```

---

### Task 4: Trace `ExperimentService` / `VerificationService` for independent prediction logic

**Files:**
- Modify: `docs/superpowers/specs/2026-08-08-dual-computation-findings.md`

**Interfaces:**
- Consumes: nothing new (independent of Tasks 1–3, can run in parallel with them).
- Produces: findings (or "traced, no divergence" notes) appended to the report.

Neither of these files has been read yet this audit — this is genuinely open investigation.
`VerificationService.evaluateEvent` is known (from `docs/superpowers/specs/2026-08-01-dosing-safety-remediation-design.md`,
finding H1) to compute `actualDelta = twoHour - baseline` from real recorded CGM readings —
that's comparing a prediction to a real outcome, which is NOT the dual-computation pattern
this audit targets (it's supposed to differ from the prediction; that's the point of
verification). The check here is narrower: does either service compute its own *predicted*
glucose/COB/IOB/ISF value independently, rather than reading it from
`GlucoseCalculationsService`'s output or `activeCobIobInputs`?

- [ ] **Step 1: Read `ExperimentService` for independent prediction/COB/IOB computation**

  Run:
  ```bash
  grep -n "predictedGlucose\|twoHourPrediction\|predictionPath\|calculateTotalCarbsOnBoard\|calculateTotalActiveInsulin\|resolveIsf\|calculatePredictedGlucose" glucose-monitor-be/src/main/java/che/glucosemonitorbe/service/ExperimentService.java
  ```
  For each match, check whether it's (a) reading a value already computed by
  `GlucoseCalculationsService`/`activeCobIobInputs` and passed in, or (b) an independent call
  that recomputes the same kind of quantity. Only (b) is a finding.

- [ ] **Step 2: Read `VerificationService` for independent prediction computation**

  Run:
  ```bash
  grep -n "predictedGlucose\|twoHourPrediction\|predictionPath\|calculateTotalCarbsOnBoard\|calculateTotalActiveInsulin\|resolveIsf\|calculatePredictedGlucose\|class VerificationService" glucose-monitor-be/src/main/java/che/glucosemonitorbe/service/VerificationService.java
  ```
  Read `evaluateEvent` (or equivalent) in full. Confirm whether "baseline"/"twoHour" (the
  values `actualDelta` is computed from) come from real recorded readings (expected, not a
  finding) vs. a re-invocation of the prediction formula (would be a finding — it would mean
  verification is partly checking the model against itself).

- [ ] **Step 3: Append results to the findings report, then commit**

  ```bash
  cd glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings.md
  git commit -m "docs: trace ExperimentService/VerificationService for independent prediction logic (dual-computation audit)"
  ```

---

### Task 5: Hovorka warm-up continuity test under active IOB/COB

**Files:**
- Modify: `glucose-monitor-be/src/test/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionServiceTest.java`
- Modify: `docs/superpowers/specs/2026-08-08-dual-computation-findings.md`

**Interfaces:**
- Consumes: existing `service`/`params`/`USER_ID`/`NOW` fields from this test class's
  `@BeforeEach` (already set up — no changes needed to `setUp()`). `InsulinDose` and
  `CarbsEntry` are already imported in this file.
- Produces: a passing or failing JUnit test, and one finding entry (or "traced, no
  divergence" note) documenting the result either way.

This reproduces the 2026-08-08 2:24 PM screenshot: a correction bolus (5.0u, ~82 min before
"now") and a meal (10g, ~34 min before "now") both still active, checking whether the first
forward-integrated point kinks away from the curve's own subsequent trend — the visible "step"
in the chart.

- [ ] **Step 1: Write the test**

  Add this test method to `HovorkaGlucosePredictionServiceTest.java`, near the existing
  `waningBasal_noCobNoIob_risesGraduallyWithoutInitialJump` test:

  ```java
  @Test
  @DisplayName("Active correction bolus + recent meal: first forward point must not kink relative to the following points' trend")
  void activeDoseAndMeal_firstEmittedPoint_noKinkAtWarmupBoundary() {
      double g0 = 6.4;

      InsulinDose correction = InsulinDose.builder()
              .timestamp(NOW.minusMinutes(82))
              .units(5.0)
              .build();
      CarbsEntry lunch = CarbsEntry.builder()
              .timestamp(NOW.minusMinutes(34))
              .carbs(10.0)
              .build();

      List<PredictionPointDTO> curve = service.buildPredictionPath(
              params, g0, NOW,
              List.of(lunch), List.of(correction), List.of(),
              USER_ID, 240);

      assertThat(curve).hasSizeGreaterThanOrEqualTo(3);

      double p0 = curve.get(0).getPredictedGlucose(); // minute 5
      double p1 = curve.get(1).getPredictedGlucose(); // minute 10
      double p2 = curve.get(2).getPredictedGlucose(); // minute 15

      double stepIntoP0 = p0 - g0;
      double stepP0toP1 = p1 - p0;
      double stepP1toP2 = p2 - p1;
      double localTrend = (stepP0toP1 + stepP1toP2) / 2.0;

      // The step from the anchor into the first emitted point should be roughly consistent
      // with the trend the curve is already following one and two steps later - not an
      // outlier kink. 0.3 mmol/L in one 5-min step is already a generous allowance (the
      // screenshot's visible jump was roughly 0.6-0.8 mmol/L in a single step).
      assertThat(stepIntoP0 - localTrend)
              .as("Step from currentGlucose (%.2f) into the first point (%.2f) is %.2f, but the "
                      + "curve's own local trend one/two steps later is %.2f - a large gap here "
                      + "is the discontinuity seen in the screenshot (correction bolus + meal "
                      + "both active), not present in the idle case", g0, p0, stepIntoP0, localTrend)
              .isCloseTo(0.0, within(0.3));
  }
  ```

- [ ] **Step 2: Run it**

  ```bash
  cd glucose-monitor-be
  ./gradlew test --tests "che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionServiceTest.activeDoseAndMeal_firstEmittedPoint_noKinkAtWarmupBoundary"
  ```
  Note the actual `p0`, `stepIntoP0`, and `localTrend` values from the assertion failure
  message if it fails — you'll need them for the report entry either way.

- [ ] **Step 3: Write the finding based on the result**

  If it **fails**: the kink is real and reproducible at the model level. Append to the
  findings report using the standard finding template — quantity = "first forward-integrated
  glucose point after 'now', under active IOB/COB", paths = the state warm-up
  (`buildWarmState`, `HovorkaGlucosePredictionService.java` — cite the exact lines your test
  run's stack trace / your reading points to) vs. the forward integration loop's first step,
  disagreement scenario = the actual numbers from the failed assertion, confidence =
  confirmed-by-reading (you have a reproducing test), status = open.

  If it **passes**: append a "traced, no divergence found" note stating the specific inputs
  tried (5.0u @ 82min, 10g @ 34min) stayed within the 0.3 mmol/L smoothness bound, and
  explicitly flag that this only rules out *this* combination — note in the same paragraph
  that the visible screenshot jump might need different timing/doses to reproduce (e.g. try
  varying minutes-ago or units in a follow-up if this is the outcome), so this isn't
  mis-read later as "the bug doesn't exist."

- [ ] **Step 4: Commit**

  ```bash
  cd glucose-monitor-be
  git add src/test/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionServiceTest.java docs/superpowers/specs/2026-08-08-dual-computation-findings.md
  git commit -m "test: add Hovorka warm-up continuity check under active IOB/COB (dual-computation audit)"
  ```

---

### Task 6: Compile and cross-check the final report

**Files:**
- Modify: `docs/superpowers/specs/2026-08-08-dual-computation-findings.md`

**Interfaces:**
- Consumes: all entries from Tasks 1–5 (must run last, after all others are committed).
- Produces: the finished findings report, plus a short summary message to relay to the user.

- [ ] **Step 1: Read the full report and the design spec side by side**

  Open `docs/superpowers/specs/2026-08-08-dual-computation-findings.md` and
  `docs/superpowers/specs/2026-08-08-dual-computation-audit-design.md` §3 ("Scope"). For each
  bullet in the spec's target list, confirm there's a corresponding entry (finding or "traced,
  no divergence") in the report. List any gap as its own "Not yet traced: <target>" line at
  the end of the report rather than silently leaving it out.

- [ ] **Step 2: Add a summary section at the top of the report**

  Prepend (after the `# ... Findings` header) a short summary: total findings count, how many
  are `open` vs `not-a-bug`, and one line per open finding naming it. This is what a human
  reviewer reads first.

- [ ] **Step 3: Commit**

  ```bash
  cd glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings.md
  git commit -m "docs: compile dual-computation audit findings summary"
  ```

- [ ] **Step 4: Report back to the user**

  Summarize the findings report contents in the conversation (not a new file) — how many open
  findings, what they are, and which (if any) look worth the test-first fix treatment next,
  per the design spec's §5 handoff.
