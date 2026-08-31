# Dual-Computation Divergence Audit — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Re-baselined 2026-08-30 against `main` @ `8b5d8c3`.** The original plan was written on
> 2026-08-08 and only Task 1 was executed (commit `6cdfdc6`). Three merges have landed since —
> dosing-path-safety (`954b685`), hypo-rescue-carb-logging (`9adc5b4`) and time-dependent-ISF
> (`8b5d8c3`) — and they invalidated the original Task 1 finding and the whole premise of the
> original Task 2. Three tasks are new. Tasks 6 and 7 (`BasalInsulinResolver`,
> `DallaManGutModel`) are named in the design spec's scope list but the original plan had no task
> for either. Task 5 (`ai/ContextAggregatorService`) and Task 8 (plasma insulin `I(t)`) are in
> neither the spec nor the original plan. Task 5 is the highest-severity target here — it
> reconstitutes the same defect `30b76bd` fixed, on a patient-facing path. Task 8 came from a
> reference-architecture document the user supplied on 2026-08-30, used strictly as a map of
> which quantities to audit (see Global Constraints for its limits). Every line number below was
> re-verified against `8b5d8c3`. See "Re-baseline log" at the end of this file.

**Goal:** Produce a findings report listing every place in `glucose-monitor-be`'s
prediction/calculation core where a logical quantity is computed via more than one independent
code path with nothing enforcing agreement — the pattern behind the 2h-forecast bug fixed in
`30b76bd` — plus a concrete pass/fail test for the "jump at now" symptom observed under active
IOB/COB.

**Architecture:** No new production code paths. One task corrects an already-committed finding
that the intervening merges made false; eight tracing tasks read specific methods/call sites and
each write their own findings fragment; two test tasks add a real JUnit test reproducing the
observed chart-jump scenario and a set of pinning tests that bind the duplicated constants
together. A final task assembles the fragments into one report, cross-checks it, opens a tracked
issue per open finding, and gates on the Definition of done.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, Mockito, AssertJ (existing test stack — no new
dependencies).

## Global Constraints

- Spec: `docs/superpowers/specs/2026-08-08-dual-computation-audit-design.md`
- Java 21 is required: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`
- Backend only (`glucose-monitor-be`) — do not touch iOS or web frontend repos. Note that
  `glucose-monitor-fe/src/services/glucosePrediction.ts` carries its own independent
  carb/insulin-contribution prediction; it is a real instance of the pattern but it is
  explicitly out of scope per spec §3. Record it in "Observed but out of scope", do not trace it.
- Pattern only: a quantity computed by >1 independent path. Do not report unrelated bug classes
  (stale caches, unit conversion, timezone handling) even if noticed in passing — note them in
  the report's "Observed but out of scope" section instead.
- **No production code changes in this plan. Test-only additions are permitted, and only in
  Tasks 9 and 10.** Task 9 adds a continuity test; if it fails, that failure IS the finding — do
  not fix the underlying code. Task 10 adds pinning tests that bind duplicated constants to each
  other so a future edit fails the build instead of drifting silently. Neither may touch
  `src/main`. Tasks 6 and 7 will surface duplicated derivations whose obvious remedy is a
  refactor: **do not refactor.** Write the finding, let Task 10 pin it, and move on; extracting a
  shared helper changes ODE behaviour risk-for-risk and belongs in its own test-first plan.
- Handling uncertainty (spec §4): if you find a genuine divergence but can't tell from the code,
  comments, or git history whether it's a bug or an intentional simplification, do not guess
  either way. Stop that task, write the divergence up as `Status: needs user input` in the report
  (quantity + both paths + why intentionality is unclear), and move on to the next task rather
  than blocking on it — the user resolves these when reviewing the compiled report in Task 11.
- **One fragment per task — never a shared file.** Each tracing task writes its findings to its
  own file under `docs/superpowers/specs/2026-08-08-dual-computation-findings/`, named in that
  task's **Files** block. No two tasks ever write the same path, which is what makes the
  "can run in parallel" claims true rather than aspirational, and what lets
  `superpowers:subagent-driven-development` dispatch these concurrently without conflicts.
  Commit only your own fragment (plus your own test file where applicable) — never batch commits
  across tasks, and never edit another task's fragment.

  **Ordering:** Task 1 runs first and alone — it creates the directory everyone else writes into.
  Tasks 2–8 may then run concurrently. Task 9 is independent and may join them. Task 10 needs the
  findings from Tasks 5, 6 and 7, so it runs after those. Task 11 runs last, alone.

  The single report at `docs/superpowers/specs/2026-08-08-dual-computation-findings.md` already
  exists with exactly one entry, and that entry is **wrong as of `8b5d8c3`** — Task 1 converts it
  into fragment `01-isf-fallback.md` and corrects it there. Task 11 concatenates every fragment
  back into that same path and deletes the fragment directory, so the report ends up exactly
  where the design spec and every existing reference expect it. Between Task 1 and Task 11 the
  report file does not exist; that is intentional, not a mistake to "fix" mid-run.
- **Line numbers in this plan are valid at `8b5d8c3`.** Each task's first step re-greps rather
  than trusting them. If a grep disagrees with a number here, trust the grep and say so in the
  finding.
- Report entry format (use exactly this shape for every finding):

  ```markdown
  ## Finding: <short name>

  **Quantity:** <what logical value this is about>
  **Paths:**
  - `path/File.java:LINE` — <method name>, <one-line description of how it computes the value>
  - `path/File.java:LINE` — <method name>, <one-line description of how it computes the value>
  **Disagreement scenario:** <concrete inputs where the two paths would produce different
  numbers, with rough values if you can compute them>
  **Reachability:** <which user-visible field, if any, carries the divergence — or "latent:
  computed and serialized but no known consumer">
  **Confidence:** confirmed-by-reading | suspected-needs-verification
  **Status:** open | not-a-bug (intentional — explain why) | needs user input (explain what's
  ambiguous)
  ```

  For a target traced and found clean, append instead:

  ```markdown
  ## Traced, no divergence found: <short name>

  <One paragraph: what you checked and why it's a single source of truth.>
  ```

  The `Reachability` line is new in this re-baseline. It exists because the original Task 1
  finding claimed a user-visible impact that a later merge removed, and nothing in the entry
  format forced that claim to be checked. Every finding from here on states reachability
  explicitly, and Task 11 cross-checks it.
- **Reference map (advisory, not ground truth).** A synthesis document —
  *"Интегративная математическая модель глюкозо-инсулинового гомеостаза"*, 22 pp., supplied by
  the user 2026-08-30 — describes the Hovorka + Dalla Man + Palumbo architecture this codebase
  implements. Use it for **one purpose only: enumerating the quantities a complete model has**,
  so target selection stops depending on the spec's 2026-08-08 §3 list (which has already missed
  three live targets). Everything this plan needs from it is transcribed below and in Task 11
  Step 3 — the plan is self-contained and you do not need the PDF to execute it.

  **Do not treat its numbers as authoritative, and do not open a code-vs-literature conformance
  review — that is a different audit.** The document is LLM-generated: its `k_empt` equation is
  unrendered, truncated LaTeX; all 55 citations share one access date; the list mixes primary
  papers with a podcast page and a MathWorks help article; it cites "Palumbo et al. 2023" where
  this codebase cites Palumbo 2026; and its "clinical validation" section validates nothing —
  it reasons about one meal and asserts its own output is correct, with no CGM comparison.

  Verified against `8b5d8c3` before this re-baseline — exact matches: `EGP₀ = 0.0161·BW`,
  `F₀₁ = 0.0097·BW`, `k₁₂ = 0.066`, `kₐ₃ = 0.03`, `F_R = 0.003·(G−9)·V_G` above 9 mmol/L,
  `f = 0.90`, `t½ = 9 + 27.5·d`, Elashoff β = 1.05/1.60/2.20, `Φ_GLP1 = 1/(1+κ·Inc)`,
  `k_abs ∝ GI/100`. Conflicts where **the code is at least as likely to be right**: `k_max`
  0.0465 vs `K_MAX = 0.0558`; `k_min` 0.0076 vs `K_MIN = 0.008`; `b` 0.69 vs `B = 0.82`;
  `c` 0.17 vs `D_LOW = 0.010` (a 17× difference); and fiber, which the document applies to
  `k_abs` while `MacroNutrientGastricModel` applies it to `t½`. A conflict is **not** a finding
  for this plan — this audit is about one quantity computed twice *inside the codebase*. If a
  conflict bothers you, note it in "Observed but out of scope" and move on.
- Two of the findings this plan expects (Tasks 6, 7) are **duplications that currently agree**:
  two code paths computing the same number from separately-written expressions. These are real
  instances of the audited pattern — the risk is that a future edit to one side desynchronizes
  them silently — but they are not live numeric bugs. Mark them
  `Status: open (latent — paths agree at 8b5d8c3, nothing enforces it)` so a reader can tell them
  apart from findings where the numbers differ today.

---

## Definition of done

The audit is complete when every line below is true. Task 11 gates on this list; do not report
the plan finished while any row fails. Each is checkable by reading, not by judgment.

- [ ] Every target in the design spec's §3 scope list has an entry in the report — a finding, a
      "traced, no divergence found" note, or an explicit `## Not yet traced:` line.
- [ ] Every row of the Task 11 quantity sweep has a verdict line.
- [ ] Every finding carries all fields of the report template, `Reachability` included, and no
      field reads "unknown" without saying what would settle it.
- [ ] Every `needs user input` entry is named in the summary section, not just buried in the body.
- [ ] Every finding with status `open` carries a GitHub issue number (Task 11), or the report
      states why it does not — issues disabled, `gh` failed, or the user declined that issue at
      the Step 6 gate.
- [ ] The pinning tests from Task 10 are green, and each names the finding it pins.
- [ ] The summary states the baseline commit the audit ran against.
- [ ] The fragment directory is gone and the report exists at
      `docs/superpowers/specs/2026-08-08-dual-computation-findings.md`.

---

### Task 1: Correct the stale ISF-fallback finding

**Files:**
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings/01-isf-fallback.md`

**Interfaces:**
- Consumes: nothing (first task).
- Produces: `01-isf-fallback.md`, the first fragment, and the fragment directory itself.
  Tasks 2–10 each write their own sibling fragment; Task 11 concatenates them. **Task 1 must
  land before any other task starts** — it creates the directory they all write into.

The committed finding says the ISF fallback divergence reaches the user through the
`predictionTrend` field, and that this field "was NOT touched by the `30b76bd` fix". Both claims
were true on 2026-08-08 and are false now: the dosing-path-safety merge (`954b685`) repointed
`determineTrend` at the prediction path. The underlying two-fallback divergence still exists —
what changed is where it surfaces. This task is a correction, not a deletion: do not remove the
finding.

- [ ] **Step 1: Confirm both `resolveIsf` implementations still diverge**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  grep -n "double resolveIsf" \
    src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java \
    src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java
  grep -n "DEFAULT_ISF" src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java
  ```

  Expected at `8b5d8c3`:
  ```
  .../service/GlucoseCalculationsService.java:260:    private double resolveIsf(UserSettingsDTO userSettings, LocalDateTime time) {
  .../hovorka/HovorkaGlucosePredictionService.java:693:    private double resolveIsf(UserSettingsDTO settings, double fallbackIsf, LocalDateTime time) {
  .../service/GlucoseCalculationsService.java:39:    private static final double DEFAULT_ISF = 1.0; // mmol/L per unit insulin
  ```

  Read both method bodies. Confirm `GlucoseCalculationsService.resolveIsf` still falls back to
  the hardcoded `DEFAULT_ISF = 1.0`, and `HovorkaGlucosePredictionService.resolveIsf` still falls
  back to a caller-supplied `fallbackIsf` (`pAdj.isf()`, the calibrated ISF). If either fallback
  has changed, write what it actually does now instead of the text below.

- [ ] **Step 2: Establish where the divergence now surfaces**

  ```bash
  grep -n "private String determineTrend" -A 24 \
    src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java
  grep -n "\.predictionTrend(trend)\|\.factors(factors)" \
    src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java
  ```

  Expected at `8b5d8c3`: `determineTrend(List<PredictionPointDTO> path, double currentGlucose,
  PredictionFactors factors, double horizonMinutes)` at line 428, reading
  `path.get(idx).getPredictedGlucose() - currentGlucose` and touching `factors` only in the
  `path == null || path.isEmpty()` fallback branch. `.predictionTrend(trend)` at 208,
  `.factors(factors)` at 214.

  So: the trend chip no longer carries the divergence, but `factors` — built by
  `calculatePredictionFactors` from the `DEFAULT_ISF`-fallback `resolveIsf` — is still
  serialized into every response. Confirm whether any client reads it:

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project
  grep -rn "factors" glucose-monitor-fe/src | grep -v glucosePrediction.ts
  ```

  Expected at `8b5d8c3`: two hits in `glucose-monitor-fe/src/services/glucoseCalculationsApi.ts`
  (a type declaration and a comment) and no render site — i.e. the field is typed but unused.
  That makes the divergence **latent**, not user-visible. Record whatever you actually find.

- [ ] **Step 3: Migrate the report into a fragment and correct it there**

  Create the fragment directory and move the existing report into it, so every later task has an
  isolated file to write and Task 11 has something to concatenate:

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  mkdir -p docs/superpowers/specs/2026-08-08-dual-computation-findings
  git mv docs/superpowers/specs/2026-08-08-dual-computation-findings.md \
         docs/superpowers/specs/2026-08-08-dual-computation-findings/01-isf-fallback.md
  ```

  Then edit `01-isf-fallback.md`: drop the `# Dual-Computation Divergence Audit — Findings`
  header (Task 11 writes the report header when it concatenates — a fragment holds entries only,
  no document title), and replace the whole
  `## Finding: ISF Fallback Divergence (affects predictionTrend)` section with:

  ```markdown
  ## Finding: ISF Fallback Divergence (latent, in the shipped `factors` block)

  > Corrected 2026-08-30. The original text of this finding claimed the divergence reached the
  > user through `predictionTrend`. That was true when written (2026-08-08, main @ `6cdfdc6`) and
  > false from `954b685` onward, which repointed `determineTrend` at the prediction path. The
  > divergence itself did not go away — only its exit route did.

  **Quantity:** the insulin sensitivity factor (ISF) used to price insulin's glucose-lowering
  effect for a given time window, when no manual per-meal-window override is set.

  **Paths:**
  - `service/GlucoseCalculationsService.java:260` — `resolveIsf(settings, time)`, falls back to
    the hardcoded `DEFAULT_ISF = 1.0` mmol/L/U (declared at :39) when no override applies. Feeds
    `calculatePredictionFactors` (:362) -> `factors.insulinContribution` -> the `factors` block
    serialized at :214.
  - `hovorka/HovorkaGlucosePredictionService.java:693` — `resolveIsf(settings, fallbackIsf,
    time)`, falls back to the caller-supplied `fallbackIsf`, always `pAdj.isf()` — the user's
    autotuned/calibrated ISF from `HovorkaParameterService`. Feeds the `predictionPath` array
    (the chart), and through it `twoHourPrediction`, `fourHourPrediction`,
    `eightHourPrediction` and `predictionTrend`.

  **Disagreement scenario:** a user with calibrated ISF far from 1.0 and no manual per-meal-window
  override. For calibrated ISF = 3.0, a 2u dose is priced at -6.0 mmol/L on the chart path and
  -2.0 mmol/L in `factors.insulinContribution`. Both numbers ship in the same JSON response.

  **Reachability:** latent. As of `8b5d8c3` every headline field is read off `predictionPath`
  (`twoHourPrediction` since `30b76bd`, `predictionTrend` since `954b685`), and
  `calculatePredictedGlucose` (:407) survives only as an empty-path fallback. `factors` is still
  serialized at :214 and typed in `glucose-monitor-fe/src/services/glucoseCalculationsApi.ts`,
  but no frontend code renders it. The divergence is therefore currently invisible to users and
  would become visible again the moment anything starts reading `factors`.

  **Confidence:** confirmed-by-reading.
  **Status:** open (latent — see Reachability).
  ```

- [ ] **Step 4: Commit**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git add -A docs/superpowers/specs/2026-08-08-dual-computation-findings.md \
             docs/superpowers/specs/2026-08-08-dual-computation-findings/
  git status --short   # expect exactly one rename + modification, nothing else
  git commit -m "docs: correct the ISF fallback finding - divergence is now latent, not in predictionTrend"
  ```

---

### Task 2: Trace the shipped `factors` block and the dead analytical predictor

**Files:**
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings/02-factors-block.md`

**Interfaces:**
- Consumes: Task 1's corrected finding (read it first — this task extends its Reachability
  analysis from ISF alone to the whole `factors` block).
- Produces: one entry appended to the same report.

This replaces the original Task 2, which asked whether `determineTrend`'s horizon matched the
trend label's implied horizon. That question is dead: `determineTrend` now indexes the same path
the chart renders, at the same `horizonMinutes`, so the horizon is consistent by construction.
The live question the original plan could not have asked is broader — `factors` is a *five-term*
analytical model (carb, insulin, baseline, trend, pre-bolus timing contributions) that runs on
every request, disagrees with the Hovorka path by construction, and is still serialized.

- [ ] **Step 1: Read `calculatePredictionFactors` in full**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  sed -n '362,406p' src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java
  ```

  For each of the five contribution terms, note in one line how it is computed and which
  parameter prices it. Pay attention to `carbContribution`: audit finding F12 (see
  `docs/superpowers/plans/2026-08-18-dosing-path-safety.md`) established that `carbRatio` has
  zero influence on the Hovorka path, so any term priced with `carbRatio` is by definition a
  second, independent model.

- [ ] **Step 2: Confirm every headline field now reads the path, and find `calculatePredictedGlucose`'s remaining callers**

  ```bash
  grep -rn "calculatePredictedGlucose(" src/main/java/
  sed -n '176,206p' src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java
  ```

  Expected at `8b5d8c3`: exactly two hits for `calculatePredictedGlucose` — the declaration at
  :407 and one call at :181, inside the `predictionPath.isEmpty()` else-branch. Establish whether
  that branch is reachable in practice: check whether `buildPredictionPath` can return an empty
  list for a valid request.

  ```bash
  grep -n "return List.of()\|return Collections.emptyList()\|isEmpty()" \
    src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java | head -20
  ```

  If no reachable empty-path case exists, `calculatePredictedGlucose` is dead code that
  nonetheless documents a competing model — say so; that is a finding about maintenance risk, not
  a live numeric divergence.

- [ ] **Step 3: Append the finding**

  Use the standard template. Quantity = "the predicted glucose delta over the horizon".
  Paths = `calculatePredictionFactors` (:362) + `calculatePredictedGlucose` (:407) as the
  analytical model, versus `buildPredictionPath` -> the Hovorka ODE as the model the chart and
  every headline field use. Disagreement scenario = pick one concrete meal + bolus and state
  which parameters each side prices it with (`carbRatio` / `DEFAULT_ISF` vs `aG` / calibrated
  ISF), noting you are not required to compute exact ODE output. Reachability = whatever Step 2
  established about `.factors(factors)` at :214 and the `:181` fallback branch. Status = follow
  the uncertainty rule: if you cannot tell from code, comments or git history whether keeping the
  analytical model as a documented fallback is intentional, mark `needs user input` rather than
  guessing.

- [ ] **Step 4: Commit**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings/02-factors-block.md
  git commit -m "docs: trace the shipped factors block vs the Hovorka path (dual-computation audit)"
  ```

---

### Task 3: Trace `confidence` and re-verify the COB/IOB single-source claim

**Files:**
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings/03-confidence-cob-iob.md`

**Interfaces:**
- Consumes: nothing new.
- Produces: one or two entries appended to the report.

`calculateConfidence` (:514) is a self-contained heuristic with one call site (:149). The COB/IOB
half of this task has grown since the original plan: two services that did not call these APIs on
2026-08-08 do now.

- [ ] **Step 1: Confirm `calculateConfidence` has a single call site**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  grep -rn "calculateConfidence(" src/main/java/
  ```

  Expected at `8b5d8c3`: exactly two hits, both in `GlucoseCalculationsService.java` — the call
  at :149 and the declaration at :514. If a second call site or a second confidence-like method
  exists anywhere, write it up as a finding instead of a "no divergence" note.

- [ ] **Step 2: Enumerate every COB/IOB computation site**

  ```bash
  grep -rn "calculateTotalCarbsOnBoard\|calculateTotalActiveInsulin" src/main/java/ \
    | grep -v "CarbsOnBoardService.java\|InsulinCalculatorService.java"
  ```

  Expected at `8b5d8c3` — four callers, and the distinction between them is the whole point:

  | Caller | Lines | Reads `activeCobIobInputs`? |
  |---|---|---|
  | `GlucoseCalculationsService` | 129, 132, 140, 141, 297, 298, 321, 322 | owns it (:239) |
  | `ExperimentService` | 71, 72, 117, 118 | yes — :67 |
  | `ai/ContextAggregatorService` | 94, 95 | **no** |
  | `IsfMealWindowProfileService` | 245 | **no** |

  The last two are new since the original plan and are the real content of this task. For each,
  read the surrounding method and determine whether it (a) reuses the same 8-hour window, the
  same nutrition-aware note converter and the same long-acting exclusion that
  `activeCobIobInputs` (:239) applies, or (b) assembles its own entry list. Only (b) is a
  finding — and check specifically whether rescue carbs are included, since `c7770e5` established
  that rescue carbs must stay out of titration paths.

  **Scope split:** `ContextAggregatorService` gets a dedicated task (Task 5) because its
  divergence goes well beyond COB/IOB inputs. Do not trace it in depth here — record it in the
  survey table with a one-line "see Task 5" pointer and spend this task on
  `IsfMealWindowProfileService`, which has no other coverage.

- [ ] **Step 3: Append results, then commit**

  Write one "traced, no divergence found" note for `confidence` (or a finding, per Step 1), and
  one entry covering the COB/IOB survey — the table from Step 2, stating for each caller whether
  it routes through `activeCobIobInputs`. If `IsfMealWindowProfileService` builds its own inputs,
  give it its own finding with a `Reachability` line. Leave `ContextAggregatorService` to Task 5.

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings/03-confidence-cob-iob.md
  git commit -m "docs: trace confidence + re-verify COB/IOB single source (dual-computation audit)"
  ```

---

### Task 4: Trace `ExperimentService` / `VerificationService` for independent prediction logic

**Files:**
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings/04-experiment-verification.md`

**Interfaces:**
- Consumes: nothing new. Independent of Tasks 2–5 and safe to run alongside them, but **after
  Task 1**, which creates the fragment directory this task writes into.
- Produces: findings (or "traced, no divergence" notes) appended to the report.

`VerificationService.evaluateEvent` computes `actualDelta = twoHour - baseline` (:212) from
recorded CGM readings — comparing a prediction to a real outcome, which is NOT the pattern this
audit targets. The narrower check: does either service compute its own *predicted*
glucose/COB/IOB/ISF value independently rather than reading it from `GlucoseCalculationsService`?

- [ ] **Step 1: Read `ExperimentService` for independent prediction computation**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  grep -n "predictedGlucose\|twoHourPrediction\|predictionPath\|calculateTotalCarbsOnBoard\|calculateTotalActiveInsulin\|resolveIsf\|calculateGlucoseData" \
    src/main/java/che/glucosemonitorbe/service/ExperimentService.java
  ```

  Expected at `8b5d8c3`: COB/IOB at 71, 72, 117, 118 (covered by Task 3) and
  `calculationsService.calculateGlucoseData(req)` at :425. That last one is the good case —
  delegation, not duplication. Read the method containing :425 and confirm it consumes the
  response rather than recomputing from it. Note that the loop at :117–118 recomputes COB/IOB at
  a series of timestamps `t`; confirm whether that series matches the timestamps the prediction
  path uses, or is an independently chosen grid.

- [ ] **Step 2: Read `VerificationService` for independent prediction computation**

  ```bash
  grep -n "predictedGlucose\|twoHourPrediction\|predictionPath\|calculateTotalCarbsOnBoard\|calculateTotalActiveInsulin\|resolveIsf\|calculateGlucoseData\|predictedDelta\|actualDelta" \
    src/main/java/che/glucosemonitorbe/service/VerificationService.java
  ```

  Expected at `8b5d8c3`: `actualDelta` at 212/221/491, `predictedDelta` at :213, and no
  prediction-building calls at all. Read the method around :212 in full and establish where
  `predictedDelta` comes from — a stored value recorded when the event was created (expected, not
  a finding) or a fresh re-invocation of a prediction formula (a finding: verification would be
  checking the model against itself). Read `baseline` and `twoHour` the same way.

- [ ] **Step 3: Append results, then commit**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings/04-experiment-verification.md
  git commit -m "docs: trace ExperimentService/VerificationService for independent prediction logic (dual-computation audit)"
  ```

---

### Task 5: Trace `ContextAggregatorService` — a second live 2-hour prediction

**Files:**
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings/05-context-aggregator.md`

**Interfaces:**
- Consumes: Task 1's corrected finding and Task 2's `factors` analysis if they have run (this
  service duplicates the same analytical model those tasks trace). Neither is a hard dependency —
  this task can run standalone.
- Produces: three or four entries appended to the report. Expect this to be the highest-severity
  task in the plan.

`ai/ContextAggregatorService` is not in the design spec's §3 scope list — it post-dates the spec.
It is nonetheless the most consequential target in this audit, because it reconstitutes the exact
bug `30b76bd` fixed. `:109-110` computes a 2-hour prediction from `carbRatio`, `isf` and a
pre-bolus timing term — the same analytical formula the dashboard headline was moved *off* — and
unlike the `factors` block from Task 2, **this one is rendered to the patient**:
`SafetyAndScoringService:53-58` emits it as the `PREDICTION_2H` pattern
("Model 2h prediction is X mmol/L"), and `LlmGatewayService:425,461` injects it into the LLM
prompt. Nothing reconciles it against the Hovorka path the chart draws.

Expect several distinct findings here. Write them as separate entries — they have different
severities and different remedies.

- [ ] **Step 1: Read the whole context builder**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  sed -n '29,141p' src/main/java/che/glucosemonitorbe/ai/ContextAggregatorService.java
  ```

  Expected at `8b5d8c3`: a private constant block at :31-35, and `buildContext(userId,
  windowHours, end)` at :53 assembling the whole `AnalysisContext` in one method.

- [ ] **Step 2: Finding A — the independent 2 h prediction**

  Read `:102-110` closely. At `8b5d8c3` it is:

  ```java
  double correctionUnits = latest > CORRECTION_TARGET_MMOl
          ? Math.max(0.0, (latest - CORRECTION_TARGET_MMOl) / isf - activeIob)
          : 0.0;
  ...
  double predicted2h = Math.clamp(
          latest + (activeCob / 10.0) * carbRatio - activeIob * isf + preBolusTimingContribution, 1.0, 25.0);
  ```

  Compare that expression term by term against `GlucoseCalculationsService.calculatePredictionFactors`
  (:362) + `calculatePredictedGlucose` (:407) — including the shared `1.0, 25.0` clamp — and against
  the Hovorka path. Note that `carbRatio` prices the carb term, which finding F12
  (`docs/superpowers/plans/2026-08-18-dosing-path-safety.md`) proved has zero influence on the ODE.

  Confirm reachability rather than assuming it:

  ```bash
  grep -rn "getPredictedGlucose2h\|getEstimatedCorrectionUnits" src/main/java/ | grep -v AnalysisContext.java
  sed -n '50,75p' src/main/java/che/glucosemonitorbe/ai/SafetyAndScoringService.java
  ```

  Expected at `8b5d8c3`: `SafetyAndScoringService:53` (pattern `PREDICTION_2H`, severity `low`)
  and `:68` (recommendation `CORRECTION_MATH_GUIDE`, priority **high**), plus
  `LlmGatewayService:425,461` and `RagRetrieverService:29-30`.

  Write the finding. Quantity = "predicted glucose 2 h ahead". Paths = `ContextAggregatorService:109`
  versus the Hovorka `predictionPath` the chart and dashboard headline use. Disagreement scenario =
  a concrete meal + bolus where the two disagree in direction; the `30b76bd` commit message and
  the design spec §1 describe the dashboard version of exactly this. Reachability = patient-facing
  via `/api/ai-insights/retrospective`, and additionally fed to the LLM as ground truth, so a wrong
  number is both displayed and reasoned from. Status = open, and say plainly that this is the same
  defect class `30b76bd` fixed, surviving in a second service.

- [ ] **Step 3: Finding B — the duplicated note→`CarbsEntry` converter**

  ```bash
  sed -n '130,168p' src/main/java/che/glucosemonitorbe/ai/ContextAggregatorService.java
  grep -rn "NoteToCarbsEntryMapper" src/main/java/
  ```

  Expected at `8b5d8c3`: a private `toCarbsEntry` (:142) and `toInsulinDose` (:157) here, while
  `GlucoseCalculationsService:57` and `GlucosePredictService:93` both inject the shared
  `service/nutrition/NoteToCarbsEntryMapper`. Two converters for one conversion.

  The javadoc at :134-140 records a bug this split already caused — the advisor was told a 15 g
  rescue still had ~11 g on board while the dashboard correctly reported it three-quarters
  absorbed — and states it was fixed by adding the rescue marker here too. Establish whether
  `toCarbsEntry` is now feature-equivalent to the mapper (GI, protein, fat, fiber, rescue marker,
  meal type) or whether other fields still diverge. A past bug of exactly this shape is strong
  evidence for the finding; note it as such.

- [ ] **Step 4: Finding C — duplicated constants and a duplicated method body**

  ```bash
  grep -n "DEFAULT_CARB_RATIO\|DEFAULT_ISF\|PRE_BOLUS_MAX_TIMING_EFFECT\|CORRECTION_TARGET" \
    src/main/java/che/glucosemonitorbe/ai/ContextAggregatorService.java \
    src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java
  diff <(sed -n '189,202p' src/main/java/che/glucosemonitorbe/ai/ContextAggregatorService.java) \
       <(sed -n '452,472p' src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java)
  ```

  Expected at `8b5d8c3`: `DEFAULT_CARB_RATIO = 2.0` and `PRE_BOLUS_MAX_TIMING_EFFECT = 1.2`
  declared privately in *both* classes, `DEFAULT_ISF = 1.0` in both (`ContextAggregatorService:32`,
  `GlucoseCalculationsService:39`), and `calculatePreBolusTimingContribution` present in both
  (`:189` and `:455`) with identical thresholds (10.0 / 25.0), identical coefficients
  (`0.6 + delta * 0.6`, `delta * 0.6`) and identical clamping — the diff should show only comments.

  This is the agreeing-but-unenforced class: same numbers today, two private declarations,
  nothing failing if one moves. Use the status convention from the Global Constraints.

  Also record `CORRECTION_TARGET_MMOl = 6.5` (:33) as its own line: it is a hardcoded correction
  target with no counterpart constant in `GlucoseCalculationsService`, and the time-dependent-ISF
  final review (C1) found `glucose-monitor-fe/src/services/carbsOnBoard.ts` using **7.0** for the
  same purpose. That is a cross-repo divergence, so per the backend-only constraint it belongs in
  "Observed but out of scope" — but name it there, do not drop it.

- [ ] **Step 5: Check the window and the ISF resolution**

  ```bash
  sed -n '53,78p' src/main/java/che/glucosemonitorbe/ai/ContextAggregatorService.java
  grep -n "activeCobIobInputs" -A 18 src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java | sed -n '1,30p'
  ```

  Two things to settle:
  - **Window.** This service selects notes with `findByUserIdAndTimestampBetween(userId, start,
    end)` where `start = end.minusHours(windowHours)` (:54, :71), while `activeCobIobInputs`
    (:239) uses its own fixed lookback. If `windowHours` is shorter than that lookback, COB/IOB
    here is computed over fewer notes than the dashboard's — the same quantity, a different window,
    no reconciliation. Determine both windows and state whether they can disagree.
  - **ISF.** `:99-101` resolves `cob.getEffectiveIsf(end)` with a `DEFAULT_ISF = 1.0` fallback.
    The comment claims it matches `InsulinCalculatorService`'s correction leg — that is the C2 fix
    from the time-dependent-ISF branch (`9b6ccd4`). Confirm the claim holds, and note that the
    `1.0` fallback is the *same* fallback Task 1's finding is about, in a third location.

- [ ] **Step 6: Append all findings, then commit**

  Append Findings A, B and C (plus the window/ISF results from Step 5, folded into A or written
  separately as the evidence warrants). Give each its own `Reachability` line — A and the
  correction estimate are patient-facing and LLM-facing; the constant duplication is latent.

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings/05-context-aggregator.md
  git commit -m "docs: trace ContextAggregatorService's independent 2h prediction (dual-computation audit)"
  ```

---

### Task 6: Trace `BasalInsulinResolver` — EGP suppression computed two ways

**Files:**
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings/06-basal-egp.md`

**Interfaces:**
- Consumes: nothing new. Independent of Tasks 2–5 and safe to run alongside them, but **after
  Task 1**, which creates the fragment directory this task writes into.
- Produces: one or two entries appended to the report.

`BasalInsulinResolver` is named in the design spec's §3 scope list and the original plan had no
task for it. It has become a dual-computation target since the spec was written: the "Gap 1"
task of the separate glucose-prediction-model-improvements plan (commit `49bcb7b`; plan at
`../../../docs/superpowers/plans/2026-07-25-glucose-prediction-model-improvements.md`, relative
to this repo) made `x3` — the EGP suppression fraction — a live ODE state variable, while
`BasalInsulinResolver` continued to compute its own `x3Basal` analytically. Two
models now answer "how suppressed is hepatic glucose production right now", and
`HovorkaGlucosePredictionService` stitches them together by overwriting one parameter and
resetting the other's state to zero.

- [ ] **Step 1: Read both suppression models**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  sed -n '40,113p' src/main/java/che/glucosemonitorbe/hovorka/BasalInsulinResolver.java
  sed -n '384,393p' src/main/java/che/glucosemonitorbe/hovorka/HovorkaOdeSolver.java
  ```

  Expected at `8b5d8c3`:
  - `BasalInsulinResolver.resolveEgpSuppression` (:60) -> `suppressionCurve` (:87): a piecewise
    profile of hours-since-injection — plateau at `PEAK_X3_BASAL = 0.40` (:41) to 20 h, linear
    taper to 0 at `BASAL_DIA_HOURS = 28.0` (:44). `netEgp` (:108) returns `egp0Abs * (1 - x3Basal)`.
  - `HovorkaOdeSolver.derivatives` (:388, :392): `dx3 = -KA3*x3 + KB3*plasmInsulin` with
    `KA3 = 0.03` (:33) and `KB3 = 0.000015` (:35), then `egp = p.egp0() * max(0, 1 - x3)`.

  Both compute `EGP = egp0 × (1 − suppression)`. They differ in what drives the suppression:
  hours-since-a-long-acting-note versus integrated plasma insulin activity.

- [ ] **Step 2: Read the seam that stitches them together**

  ```bash
  sed -n '283,307p' src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java
  ```

  Expected at `8b5d8c3`: `x3Basal` at :285, `egpNow = basalResolver.netEgp(...)` at :287, the
  empty-notes guard `egpNow = p.f01()` at :290–292, the re-parameterisation
  `new HovorkaParameters(p.vG(), p.f01(), egpNow, egpNow, ...)` at :299–301 — which sets **both**
  `egpNet` and `egp0` to the already-suppressed `egpNow` — and `state.withX3(0.0)` at :306.

  Answer, in the finding: with `egp0` already reduced by basal suppression and the ODE's own `x3`
  restarting from 0, does a bolus-driven `x3` rise suppress an already-suppressed EGP a second
  time? The comment at :294–299 concedes the fasting case "is not improved here" and defers a
  basal PK compartment to future work — which reads as a known, accepted simplification. Decide
  whether it is *documented as intentional* (then `not-a-bug`, cite the comment) or merely
  *described* without the double-suppression question being addressed (then `needs user input`).
  Do not guess.

- [ ] **Step 3: Check whether `PEAK_X3_BASAL` still matches the constants it is derived from**

  The class javadoc (:34–35) states `PEAK_X3_BASAL` is derived as
  `x3 = 1 - F01/EGP0 = 1 - 0.0097/0.0161 ≈ 0.40`. That is a hardcoded copy of a quantity two
  other constants determine.

  ```bash
  grep -n "F01_PER_KG\|EGP0_PER_KG" src/main/java/che/glucosemonitorbe/hovorka/HovorkaParameters.java
  grep -rn "PEAK_X3_BASAL" src/main/java/ src/test/java/
  ```

  Expected at `8b5d8c3`: `F01_PER_KG = 0.0097` (:57), `EGP0_PER_KG = 0.0161` (:58) —
  `1 - 0.0097/0.0161 = 0.3975`, so `0.40` is consistent today. Confirm the arithmetic yourself,
  then check whether any test binds them. If no test does, this is a finding: three constants
  encode one relationship and nothing fails when they drift apart. Status
  `open (latent — paths agree at 8b5d8c3, nothing enforces it)`.

- [ ] **Step 4: Confirm the other two `basalResolver` holders route through the same seam**

  ```bash
  grep -rn "basalResolver" src/main/java/che/glucosemonitorbe/service/UnloggedEventDetectionService.java \
                           src/main/java/che/glucosemonitorbe/service/DigitalTwinCalibrationService.java
  ```

  Expected at `8b5d8c3`: `UnloggedEventDetectionService` holds it at :74 and passes it at :109;
  `DigitalTwinCalibrationService` at :72 and :172. Both look like constructor plumbing into a
  prediction-service instance rather than independent suppression logic. Read both call sites and
  confirm neither calls `resolveEgpSuppression` or `netEgp` itself. If one does, that is a third
  independent EGP path and gets its own finding.

- [ ] **Step 5: State the counterfactual the reference map supplies**

  The seam exists because there is no basal insulin PK compartment to carry steady-state
  suppression — `HovorkaGlucosePredictionService:294-299` says so outright ("requires a
  steady-state PK compartment (future work)"). The reference map names what that compartment
  would be: a two-stage subcutaneous depot `S₁ → S₂` with absorption time constant `τ_S`, feeding
  `dI/dt = U_I/V_I − k_e·I`, so `x₃` is driven by a modelled plasma insulin rather than
  re-parameterised around. Quote that in the finding as the *shape* of the resolution — one
  sentence, flagged as unverified against primary sources.

  Do not adopt the map's parameter values (`τ_S ≈ 40–55 min`, `V_I = 0.12 L/kg`,
  `k_e = 0.138 min⁻¹`) as anything but an illustration, and do not implement any of it here.
  The point is to record that the double-suppression question has a known structural answer,
  so the finding reads as "deferred with a known remedy" rather than "unexplained".
  Task 8 traces the same missing compartment from the insulin side.

- [ ] **Step 6: Append results, then commit**

  Write the EGP two-model finding (quantity = "EGP suppression fraction / net EGP at time t",
  paths = `BasalInsulinResolver.suppressionCurve:87` + `netEgp:108` versus
  `HovorkaOdeSolver.derivatives:388,392`, seam =
  `HovorkaGlucosePredictionService:285-306`), plus the `PEAK_X3_BASAL` constant finding from
  Step 3 if it stands. Reachability for both: the `predictionPath` array and every headline field
  read off it — this is the live chart path, not a latent one.

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings/06-basal-egp.md
  git commit -m "docs: trace BasalInsulinResolver vs the ODE's dynamic x3 (dual-computation audit)"
  ```

---

### Task 7: Trace `DallaManGutModel` — effective gut rates derived in three places

**Files:**
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings/07-gut-rates.md`

**Interfaces:**
- Consumes: nothing new. Independent of Tasks 2–6 and safe to run alongside them, but **after
  Task 1**, which creates the fragment directory this task writes into.
- Produces: one entry appended to the report.

`DallaManGutModel` is the second design-spec §3 target the original plan had no task for. The
model class itself is a pure function library — the duplication is in its *callers*. Three
separate blocks derive the same four effective rates (`kGriEff`, `kMaxEff`, `kMinEff`,
`kAbsEff`) from the same constants, and one of them carries a comment asserting it is "identical
to derivatives()" with nothing testing that claim.

- [ ] **Step 1: Read the two full derivations side by side**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  sed -n '347,371p' src/main/java/che/glucosemonitorbe/hovorka/HovorkaOdeSolver.java
  sed -n '585,600p' src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java
  ```

  Expected at `8b5d8c3` — the ODE's own derivation (`derivatives`, :348–364) and the warm-up
  replay's hand-rolled copy (:586–598). Both compute `cCal`, `giScale`, the three emptying rates,
  `kAbsEff`, `kempt` and `kemptEff` from the same `DallaManGutModel` constants.

- [ ] **Step 2: Diff the two derivations line by line and record every delta**

  Four candidate deltas are visible at `8b5d8c3` — confirm each against what you just read, and
  look for any the list misses:

  | | `HovorkaOdeSolver.derivatives` | warm-up replay |
  |---|---|---|
  | half-life factor | bare literal `1.68` (:349) | named `HovorkaParameterService.HALF_LIFE_TO_TMAX_G` (:588) |
  | `cCal` source | `p.tMaxG()` (:349) | `activeTMaxG`, rescue-aware (:587) |
  | `kEmpt` meal reference | `mealMmol` (:364) | `dRef`, refreshed at :565, guarded `dRef > 0 ? ... : 0.0` (:596) |
  | `kAbsEff` source | `p.tMaxG()` (:361) | `activeTMaxG` (:593) |

  ```bash
  grep -rn "HALF_LIFE_TO_TMAX_G" src/main/java/
  grep -n "1\.68" src/main/java/che/glucosemonitorbe/hovorka/HovorkaOdeSolver.java
  ```

  Expected: `HALF_LIFE_TO_TMAX_G = 1.68` declared at `HovorkaParameterService:70` and used by
  four callers; `HovorkaOdeSolver:349` is the one site using the bare literal. Same value today,
  so no numeric divergence — but editing the named constant silently desynchronizes the ODE from
  every path that respects it. That is the audited pattern exactly.

  For the `activeTMaxG` / `dRef` deltas, decide whether each is a genuine divergence or a
  deliberate difference in what the replay is modelling (it replays *past* meals including
  rescue carbs, which the forward ODE step does not). The `:585–586` comment claims the two are
  "identical"; if the deltas are deliberate, the finding is that the comment overstates it.

- [ ] **Step 3: Find the third, display-only derivation**

  ```bash
  sed -n '440,445p' src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java
  ```

  Expected at `8b5d8c3`: `kAbsDisplay = DallaManGutModel.effectiveKAbs(pStep.tMaxG()) *
  giScaleDisplay` (:443) and `carbEffect = gutModel.ra(state.qgut(), kAbsDisplay) *
  DENSE_STEP_MIN` (:444) — a partial fourth copy of the `ra` calculation whose output ships to
  the client as `PredictionPointDTO.carbAbsorptionEffect`. The comment at :441–442 explains it
  uses `pStep` rather than `pAdj` deliberately. Confirm whether `kAbsDisplay` can differ from the
  `kAbsEff` the ODE actually integrated with on the same step — that would mean the reported
  carb-absorption effect describes a slightly different absorption than the curve it annotates.

- [ ] **Step 4: Check whether any test binds the derivations together**

  ```bash
  grep -rn "kAbsEff\|kMaxEff\|kGriEff\|caloricScale\|effectiveKAbs" src/test/java/ | head -20
  ```

  Note whether the existing `DallaManGutModelTest`, `HovorkaOdeSolverTest` or
  `GutModelMealTypeTest` assert on the *model functions* only (each derivation still free to
  drift) or on agreement between the ODE and the replay. This determines the finding's status:
  duplicated-and-untested versus duplicated-but-pinned.

- [ ] **Step 5: Append the finding, then commit**

  Quantity = "the effective gut rates `kGriEff` / `kMaxEff` / `kMinEff` / `kAbsEff` at a given
  step". Paths = `HovorkaOdeSolver.derivatives:348-364`, `HovorkaGlucosePredictionService:586-598`
  (warm-up replay), `HovorkaGlucosePredictionService:443-444` (display-only `ra`). Disagreement
  scenario = whichever deltas survived Step 2, plus the `1.68`-literal drift scenario stated as
  a hypothetical edit rather than a current bug. Reachability = `predictionPath` and
  `PredictionPointDTO.carbAbsorptionEffect`. Status per the Global Constraints rule for
  agreeing-but-unenforced duplications.

  **Do not extract a shared helper.** The remedy is out of scope per the Global Constraints; note
  it as the obvious follow-up in the finding and leave the code alone.

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings/07-gut-rates.md
  git commit -m "docs: trace the three DallaManGutModel rate derivations (dual-computation audit)"
  ```

---

### Task 8: Trace plasma insulin `I(t)` — two PK lineages joined by a scale factor

**Files:**
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings/08-plasma-insulin.md`

**Interfaces:**
- Consumes: nothing new (independent of Tasks 1–7). Task 6 Step 5 covers the same missing
  compartment from the EGP side; if it has run, cross-reference rather than restate.
- Produces: one entry appended to the report.

This target came from the reference map and is in neither the design spec nor any earlier version
of this plan. The map devotes two pages to plasma insulin as a modelled state: a subcutaneous
depot `S₁ → S₂` with time constant `τ_S`, then `dI/dt = U_I/V_I − k_e·I`. This codebase has no
such compartment. `HovorkaOdeSolver:378` says so directly — *"Full S1->S2->I PK model is
deferred"* — and substitutes a single multiplication.

So `I(t)` — a physical quantity the ODE depends on — is produced by a pharmacokinetic model from
an entirely different lineage (the OpenAPS exponential IOB curve, parameterised by the user's
DIA and peak) and converted into the Hovorka model's units by one empirical constant. That is
two independent models of one quantity with a magic number in between, which is this audit's
pattern in its purest form.

- [ ] **Step 1: Read the bridge and its constant**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  sed -n '30,40p' src/main/java/che/glucosemonitorbe/hovorka/HovorkaOdeSolver.java
  sed -n '374,392p' src/main/java/che/glucosemonitorbe/hovorka/HovorkaOdeSolver.java
  ```

  Expected at `8b5d8c3`: `V_I_SCALE = 12.0` at :37, commented "Empirical bridge: mU/L per
  normalised insulin effect rate unit"; `plasmInsulin = insulinActivityRate * V_I_SCALE` at :385;
  `dx3 = -KA3 * x3 + KB3 * plasmInsulin` at :388 with `KB3 = 0.000015` derived as `KA3 × S_IE`.

  Note what `V_I_SCALE` is doing dimensionally: converting units/min of IOB activity into mU/L of
  plasma insulin. Establish whether anything documents where 12.0 came from — a fit, a paper, or
  a value chosen to make the curves look right. `git log -S V_I_SCALE` is the fastest route.

- [ ] **Step 2: Trace where `insulinActivityRate` is produced**

  ```bash
  grep -n "insulinActivityRate" src/main/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionService.java
  grep -n "iobOpenApsExponential" -A 20 src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java
  ```

  Expected at `8b5d8c3`: accumulated at `HovorkaGlucosePredictionService:375-382` from per-dose
  activity, ultimately from `InsulinCalculatorService.iobOpenApsExponential` (:78) parameterised
  by `diaHours` / `peakMinutes` from the user's rapid-insulin catalog. Confirm the chain.

  Then state plainly in the finding: the ODE's insulin action (`x₁`, `x₂`, `x₃` via `KB3`) is
  driven by an OpenAPS curve, while every other coefficient in `derivatives()` is Hovorka-lineage.
  Two parameter sets from two papers meeting at one multiplication.

- [ ] **Step 3: Establish whether the two lineages can disagree**

  The sharpest question is not "is 12.0 right" but "can the two models be inconsistent with each
  other". Work out what happens when a user changes their insulin preferences:

  ```bash
  grep -rn "getRapidIobParameters" src/main/java/ | head
  ```

  If `diaHours` / `peakMinutes` are user-editable, then editing them reshapes
  `insulinActivityRate` and therefore `plasmInsulin`, `x₃` and `EGP` — while `KA3`, `KB3` and
  `V_I_SCALE` stay fixed at their Hovorka-population values. Determine whether any combination of
  user settings drives `plasmInsulin` outside the range `KB3` was calibrated for. If it can, that
  is a live divergence, not a latent one: the ODE would be integrating an insulin signal its own
  constants were never fitted against. State the reachability accordingly.

- [ ] **Step 4: Append the finding, then commit**

  Quantity = "plasma insulin concentration `I(t)` driving `x₁`/`x₂`/`x₃`". Paths =
  `InsulinCalculatorService.iobOpenApsExponential:78` (OpenAPS exponential IOB, user-parameterised)
  versus the Hovorka `S₁→S₂→I` PK model the reference architecture specifies and this codebase does
  not implement, joined at `HovorkaOdeSolver:385` by `V_I_SCALE = 12.0`. Disagreement scenario =
  whatever Step 3 established. Reachability = `predictionPath` and every field read off it.
  Status = open if Step 3 found a reachable inconsistency, otherwise
  `open (latent — see Reachability)`. Cross-reference Task 6: the same absent compartment is why
  the EGP seam re-parameterises `egp0` instead of letting `x₃` carry basal suppression.

  Per the Global Constraints: **do not implement the PK compartment**, and do not file the
  map's `τ_S` / `V_I` / `k_e` values as if they were validated. Naming the gap is the deliverable.

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings/08-plasma-insulin.md
  git commit -m "docs: trace the V_I_SCALE bridge between OpenAPS IOB and Hovorka insulin action (dual-computation audit)"
  ```

---

### Task 9: Hovorka warm-up continuity test under active IOB/COB

**Files:**
- Modify: `src/test/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionServiceTest.java`
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings/09-warmup-continuity.md`

**Interfaces:**
- Consumes: the existing `service` / `params` / `USER_ID` (:52) / `NOW` (:53) fields from this
  test class's `@BeforeEach` (:56) — no changes to `setUp()`. `CarbsEntry`, `InsulinDose`,
  `PredictionPointDTO`, `List`, `assertThat` and `within` are all already imported. Task 7's
  reading of the warm-up replay (:552–614) is useful context but not a dependency.
- Produces: a passing or failing JUnit test, and one report entry documenting the result either
  way.

This reproduces the 2026-08-08 2:24 PM screenshot: a correction bolus (5.0u, ~82 min before
"now") and a meal (10g, ~34 min before "now") both still active, checking whether the first
forward-integrated point kinks away from the curve's own subsequent trend.

**Re-baseline note:** the ODE has changed since this test was designed — `62dd077` rewired the x3
bridge to be driven by insulin activity rather than an ISF quotient, and `0a3c32c` reconciled ISF
wall times with UTC CGM epochs. The 8-argument `buildPredictionPath(customParams, ...)` overload
the test calls still exists at :181 with an unchanged signature, so the code below compiles as
written. But the original plan's implicit expectation — that this test would fail and confirm the
kink — no longer carries any weight. Treat both outcomes as genuinely open.

- [ ] **Step 1: Write the test**

  Add this method to `HovorkaGlucosePredictionServiceTest.java`, near the existing
  `waningBasal_noCobNoIob_risesGraduallyWithoutInitialJump` test (:106):

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
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
  ./gradlew test --tests "che.glucosemonitorbe.hovorka.HovorkaGlucosePredictionServiceTest" -q
  ```

  Record the actual `p0`, `stepIntoP0` and `localTrend` values — from the assertion failure
  message if it fails, or by adding a temporary `System.out.println` and re-running if it passes.
  You need them for the report entry either way. Remove any temporary printing before committing.

- [ ] **Step 3: Write the finding based on the result**

  If it **fails**: the kink is real and reproducible at the model level. Append a finding —
  quantity = "first forward-integrated glucose point after 'now', under active IOB/COB", paths =
  the state warm-up versus the forward integration loop's first step (cite the exact
  `HovorkaGlucosePredictionService` lines your reading points to; do not reuse a line number from
  this plan for code you have not read), disagreement scenario = the actual numbers from the
  failed assertion, reachability = the chart's first rendered point, confidence =
  confirmed-by-reading, status = open. If Task 7 already ran, cross-reference any rate delta it
  found between the replay and `derivatives` — that is a candidate mechanism for the kink.

  If it **passes**: append a "traced, no divergence found" note stating the exact inputs tried
  (5.0u @ 82min, 10g @ 34min, g0 = 6.4) and the measured gap, and explicitly flag that this rules
  out only *this* combination. State in the same paragraph that the ODE was rewired by `62dd077`
  after the screenshot was taken, so a pass here may mean the kink was fixed in passing rather
  than that it never existed — and that reproducing the original symptom would need a checkout at
  `6cdfdc6`. Do not let a pass be read later as "the bug doesn't exist".

- [ ] **Step 4: Commit**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git add src/test/java/che/glucosemonitorbe/hovorka/HovorkaGlucosePredictionServiceTest.java \
          docs/superpowers/specs/2026-08-08-dual-computation-findings/09-warmup-continuity.md
  git commit -m "test: add Hovorka warm-up continuity check under active IOB/COB (dual-computation audit)"
  ```

---

### Task 10: Pin the duplications so they fail instead of drifting

**Files:**
- Create: `src/test/java/che/glucosemonitorbe/hovorka/DuplicatedConstantPinningTest.java`
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings/10-pinning-tests.md`

**Interfaces:**
- Consumes: the findings from Tasks 6 and 7 (which duplications exist) and Task 5 Step 4 (the
  duplicated constants in `ContextAggregatorService`). **Must run after those three.**
- Produces: a passing test class that fails the build if any pinned duplication drifts, plus a
  fragment recording what is pinned and what could not be.

Tasks 5, 6 and 7 each surface a *duplication that currently agrees*: two declarations of one
number, nothing enforcing that they stay equal. A markdown finding does not prevent the drift —
this plan exists because a markdown finding rotted unnoticed for 22 days. A test does. This task
converts the agreeing-but-unenforced findings into assertions, which is the only part of this
audit that keeps working after everyone stops reading the report.

Test-only: nothing in `src/main` may change. If pinning something would require production code
to move, that is a finding about testability, not a licence to refactor.

- [ ] **Step 1: Pin `PEAK_X3_BASAL` to the constants it is derived from**

  `BasalInsulinResolver`'s javadoc derives `PEAK_X3_BASAL` as `1 − F01/EGP0`. Bind them:

  ```java
  @Test
  @DisplayName("PEAK_X3_BASAL stays consistent with 1 - F01_PER_KG/EGP0_PER_KG")
  void peakX3Basal_matchesItsDerivation() {
      double derived = 1.0 - HovorkaParameters.F01_PER_KG / HovorkaParameters.EGP0_PER_KG;
      // The declared constant is the derivation rounded to 2 dp (0.3975 -> 0.40). The tolerance
      // pins the RELATIONSHIP, not the literal: move F01 or EGP0 and this fails, which is the
      // point. Do not widen it to make a future edit pass - re-derive the constant instead.
      assertThat(BasalInsulinResolver.PEAK_X3_BASAL)
              .as("PEAK_X3_BASAL (%s) is documented as 1 - F01/EGP0 = %s; one of the three moved",
                      BasalInsulinResolver.PEAK_X3_BASAL, derived)
              .isCloseTo(derived, within(0.005));
  }
  ```

  Run it and confirm it passes at `8b5d8c3` (`0.40` vs `0.3975`, gap `0.0025`). If it fails, the
  drift has already happened — that is a finding for Task 6, not a tolerance to widen.

- [ ] **Step 2: Pin the constants duplicated between `GlucoseCalculationsService` and `ContextAggregatorService`**

  Both classes privately declare `DEFAULT_CARB_RATIO = 2.0`, `DEFAULT_ISF = 1.0` and
  `PRE_BOLUS_MAX_TIMING_EFFECT = 1.2`. Read them by reflection — the codebase already reflects on
  privates in `GlucoseCalculationsServiceTest` — and assert each pair is equal. One assertion per
  constant, each naming both declaration sites in its `.as(...)` message so a failure says where
  to look.

- [ ] **Step 3: Pin the duplicated `calculatePreBolusTimingContribution` bodies**

  The two copies (`GlucoseCalculationsService:455`, `ContextAggregatorService:189`) are private and
  byte-identical apart from comments. Pin behaviour, not text: invoke both by reflection over a
  range that crosses every branch — `null`, 0, 5, 9.9, 10, 20, 25, 25.1, 45, 100 minutes — and
  assert equal outputs. This is the strongest pin in the task: it survives either copy being
  rewritten, and only fails when they stop agreeing.

- [ ] **Step 4: Attempt the gut-rate pin, and record honestly if it cannot be done**

  Task 7's finding is that `HovorkaOdeSolver.derivatives:348-364` and the warm-up replay at
  `HovorkaGlucosePredictionService:586-598` derive the same four rates from separately-written
  expressions, and that `HovorkaOdeSolver:349` uses a bare `1.68` where every other site uses
  `HovorkaParameterService.HALF_LIFE_TO_TMAX_G`.

  The strong pin is behavioural: feed identical inputs to both derivations and assert the four
  rates match. Establish whether that is reachable — the replay block is inline inside a long
  private method, and **the no-refactor rule stands even here**. If it is not reachable without
  moving production code, do not force it. Fall back to:

  ```java
  @Test
  @DisplayName("HALF_LIFE_TO_TMAX_G is the single source for the 2-compartment factor")
  void halfLifeToTMaxG_pinnedAtItsPublishedValue() {
      // HovorkaOdeSolver:349 hardcodes 1.68 instead of reading this constant (audit finding,
      // Task 7). Until that literal is unified, this test makes a change to the named constant
      // fail loudly rather than silently desynchronising the ODE from the replay.
      assertThat(HovorkaParameterService.HALF_LIFE_TO_TMAX_G).isEqualTo(1.68);
  }
  ```

  Say plainly in the fragment which of the two you achieved. A weak pin honestly labelled is
  useful; a weak pin described as a strong one is how the last finding rotted.

- [ ] **Step 5: Run the full suite**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
  ./gradlew test --tests "che.glucosemonitorbe.hovorka.DuplicatedConstantPinningTest" -q
  ./gradlew test -q
  ```

  Every pinning test must be **green** at `8b5d8c3` — these pin the status quo, they do not
  report bugs. A red pinning test means either the finding it encodes is wrong or the drift
  already happened; resolve that before committing, and say which it was.

- [ ] **Step 6: Write the fragment and commit**

  In `10-pinning-tests.md`, one line per pin: which finding it binds, which test method, and
  whether the pin is strong (behavioural) or weak (value-only). List anything Tasks 5–7 found
  that you could **not** pin, with the reason — that list is what the next audit starts from.

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git add src/test/java/che/glucosemonitorbe/hovorka/DuplicatedConstantPinningTest.java \
          docs/superpowers/specs/2026-08-08-dual-computation-findings/10-pinning-tests.md
  git commit -m "test: pin the duplicated constants surfaced by the dual-computation audit"
  ```

---

### Task 11: Compile and cross-check the final report

**Files:**
- Create: `docs/superpowers/specs/2026-08-08-dual-computation-findings.md` (from the fragments)
- Delete: `docs/superpowers/specs/2026-08-08-dual-computation-findings/` (the whole directory)

**Interfaces:**
- Consumes: all fragments from Tasks 1–10 (must run last, after all others are committed).
- Produces: the finished findings report, plus a short summary message to relay to the user.

- [ ] **Step 1: Assemble the report from the fragments**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  ls docs/superpowers/specs/2026-08-08-dual-computation-findings/
  ```

  Expect ten fragments, `01-` through `10-`. **A missing fragment means its task did not run** —
  stop and say which, rather than compiling a report that silently omits a target.

  Write `docs/superpowers/specs/2026-08-08-dual-computation-findings.md` as: the
  `# Dual-Computation Divergence Audit — Findings` header, the summary section from Step 5
  (leave a placeholder now, fill it there), then every fragment's body in numeric order. Read
  each fragment as you go — you need their contents for Steps 2–6 anyway, and concatenating
  blind is how a malformed entry survives to the final report.

  Do not delete the fragment directory yet; Step 8 removes it in the same commit that adds the
  assembled report, so the rename is legible in one diff.

- [ ] **Step 2: Cross-check the report against the spec's target list**

  Open the report and `docs/superpowers/specs/2026-08-08-dual-computation-audit-design.md` §3
  ("Scope", lines 44–64) side by side. Confirm each in-scope target now has a corresponding entry:

  | Spec target | Covered by |
  |---|---|
  | `GlucoseCalculationsService` | Tasks 1, 2, 3, 5 |
  | `HovorkaGlucosePredictionService` | Tasks 1, 6, 7, 8, 9 |
  | `BasalInsulinResolver` | Task 6 |
  | `DallaManGutModel` | Task 7 |
  | `CarbsOnBoardService`, `InsulinCalculatorService` | Tasks 3, 5 |
  | `ExperimentService` | Tasks 3, 4 |
  | `predictionPath[0]` continuity | Task 9 |

  Add any gap you find as its own `## Not yet traced: <target>` line rather than silently leaving
  it out.

  Two services now in the dual-computation blast radius are **not** in the spec's list, because
  they did not call these APIs when it was written: `ai/ContextAggregatorService` (traced in
  depth by Task 5) and `IsfMealWindowProfileService` (Task 3). Add a
  `## Scope addendum (2026-08-30)` section naming them and stating that the spec predates them,
  so a later reader does not read the spec's list as exhaustive. Task 5's findings are the most
  consequential in this report — say so in the addendum rather than burying them under a
  target the spec never named.

- [ ] **Step 3: Second cross-check — by quantity, not by class**

  The spec's list is a list of *files*, and that is how it missed three live targets. This step
  re-checks the assembled report against the *quantities* a complete glucose-insulin model contains,
  taken from the reference map (see Global Constraints). For each row, answer: **how many places
  in the codebase compute this, and does anything enforce agreement?** One line per row in the
  report, under `## Quantity sweep (2026-08-30)`.

  | Quantity | Where the audit already looked |
  |---|---|
  | `G(t)` plasma glucose / `Q₁`,`Q₂` | Tasks 2, 5 (analytical predictors vs the ODE) |
  | `EGP(t)` and its suppression `x₃` | Task 6 |
  | `F₀₁,c` non-insulin-dependent uptake | not traced — check |
  | `F_R` renal clearance | not traced — check |
  | `x₁`, `x₂` insulin action on transport/utilisation | not traced — check |
  | `I(t)` plasma insulin | Task 8 |
  | `S₁`,`S₂` subcutaneous depot | Task 8 (absent by design) |
  | `Q_sto1`,`Q_sto2`,`Q_gut` and `k_empt` | Task 7 |
  | `U_G` / `Ra` glucose appearance | Task 7 (three `ra` derivations) |
  | `Inc` GLP-1 / ileal brake | not traced — check |
  | `t½`, caloric density `d`, Elashoff `β` | Task 7 (`caloricScale`); `MacroNutrientGastricModel` not traced |
  | GI scaling | Task 7 |
  | `f` bioavailability | not traced — check |
  | COB / IOB (no reference-model counterpart; app-level) | Tasks 3, 5 |
  | ISF | Tasks 1, 5 |
  | `τ_pause` pre-bolus | not traced — `PreBolusResolver` vs the iOS timer |

  A "check" is a grep, not an investigation: if exactly one method computes the quantity and
  every caller reads it, write one line saying so. Anything with two or more producers that this
  plan has no task for becomes a `## Not yet traced: <quantity>` entry — do **not** open new
  tracing work here. This step exists to make the report's coverage claim honest, and to hand the
  next audit a starting list instead of a file inventory.

- [ ] **Step 4: Verify every `Reachability` line**

  For each finding with `Reachability` other than "latent", re-run the grep that established it.
  This step exists because the original Task 1 finding shipped a reachability claim that a later
  merge silently falsified, and nobody noticed for 22 days. If a reachability claim no longer
  holds, correct it now rather than filing it.

- [ ] **Step 5: Add a summary section at the top**

  Prepend, after the `# Dual-Computation Divergence Audit — Findings` header: the baseline commit
  the audit was run against (`git rev-parse --short HEAD`), total findings count, how many are
  `open` vs `latent` vs `not-a-bug` vs `needs user input`, and one line per open finding naming it
  and its reachability. Separate the *live numeric divergences* from the *agreeing-but-unenforced
  duplications* (Tasks 6, 7) — they need different remedies and a reader must not confuse them.
  This is what a human reviewer reads first.

- [ ] **Step 6: Draft the issue manifest — then STOP and get approval**

  This is the step that decides whether the audit matters in a month. The 2026-08-18 parameter
  audit ended with a table naming Plans 2–6 over roughly twenty findings (F1–F21); thirteen days
  later none of those five plans existed. A findings document with no owner is the failure mode
  this audit is itself investigating, one level up. So the findings leave the repo — but they
  leave it onto a public tracker, which is why this step ends in a hard stop.

  First check the tracker is usable at all:

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  gh repo view --json nameWithOwner,hasIssuesEnabled
  ```

  If `hasIssuesEnabled` is `false`, skip both this step and Step 7 and go straight to the
  fallback at the end of Step 7.

  Otherwise build the **manifest**: for each finding whose status is `open` (not `not-a-bug`, not
  a "traced, no divergence" note) one row giving the issue title
  (`dual-computation: <finding name>`), the label, and a one-line body summary. Include the
  tracking index issue as its own row, and note whether the `dual-computation-audit` label
  already exists. Then present the whole manifest in the conversation — the repo it will post to,
  the exact count, and every title.

  <HARD-GATE>
  **Do not run `gh issue create` or `gh label create` until the user has replied approving this
  manifest.** Creating issues is outward-facing and public: it notifies watchers, it is visible
  to anyone who can see the repository, and closing an issue does not undo having opened it.
  Present the manifest and wait. This gate stands even under a standing instruction to work
  autonomously without confirmation — the user asked for it specifically, on this step, because
  the action leaves the machine.
  </HARD-GATE>

  Handle the reply as given: approval of the whole manifest, approval of a named subset (create
  only those, and record the rest in the Step 7 fallback section), or a decline (skip Step 7
  entirely and use the fallback for everything). Do not re-ask, and do not widen the approval —
  an approved subset is not a licence for the rest.

- [ ] **Step 7: Create the approved issues, and link them back into the report**

  Only the issues the user approved in Step 6. Create the label first if the manifest said it was
  missing:

  ```bash
  gh label create dual-computation-audit \
    --description "Findings from the 2026-08-08 dual-computation audit"
  ```

  Then, per approved finding:

  ```bash
  gh issue create \
    --title "dual-computation: <finding name>" \
    --label "dual-computation-audit" \
    --body "<the finding entry verbatim, plus: audited at 8b5d8c3; see docs/superpowers/specs/2026-08-08-dual-computation-findings.md>"
  ```

  Then open the tracking issue titled `dual-computation audit: findings index`, listing every
  issue number with its finding name, its reachability, and whether Task 10 pinned it.

  Finally, **write each issue number back into the report** next to its finding
  (`**Tracked:** #NNN`). The report and the tracker must point at each other; a one-way link
  rots the same way a bare document does.

  Judgment on volume, applied when you build the manifest in Step 6: `needs user input` findings
  get an issue too — they are exactly the ones that need a human. Latent findings get an issue,
  labelled as latent in the body. `not-a-bug` entries and clean traces get none.

  **Fallback** — used if issues are disabled, if `gh` fails, or for anything the user did not
  approve: add a `## Tracking` section to the report naming which findings are tracked (with
  numbers) and which are not, and why. List every untracked open finding as an explicit unowned
  backlog. Then say so prominently in Step 9 — a partially or wholly untracked audit is a
  materially weaker outcome and the user should hear it, not discover it later.

- [ ] **Step 8: Commit**

  ```bash
  cd /Users/vlad/IdeaProjects/glucose-monitor-project/glucose-monitor-be
  git rm -r --quiet docs/superpowers/specs/2026-08-08-dual-computation-findings/
  git add docs/superpowers/specs/2026-08-08-dual-computation-findings.md
  git status --short   # expect: 10 deletions + 1 added/modified report, nothing else
  git commit -m "docs: compile dual-computation audit findings report"
  ```

- [ ] **Step 9: Walk the Definition of done, then report back**

  Go to the `## Definition of done` section at the top of this plan and check every row against
  what actually exists — the assembled report, the issue list, the pinning tests, the deleted
  fragment directory. **Do not report the audit complete while any row fails.** If a row cannot
  be satisfied, say which and why; a named gap is a result, a silent one is how this plan's own
  Task 1 finding survived being wrong for 22 days.

  Then summarize in the conversation (not a new file): how many open findings, what they are,
  which are live versus latent, the tracking issue number, and which findings Task 10 could not
  pin. Flag any `needs user input` entries explicitly — those block the handoff decision in the
  design spec's §5. If issues could not be created, lead with that rather than burying it.

---

## Re-baseline log (2026-08-30, `6cdfdc6` -> `8b5d8c3`)

| Original | Now | Why |
|---|---|---|
| Task 1 — write the ISF-fallback finding | Task 1 — **correct** it | `954b685` repointed `determineTrend` at the prediction path, falsifying the finding's stated impact and its "not touched by `30b76bd`" claim. The divergence survives; only its exit route changed, from `predictionTrend` to the unrendered `factors` block. |
| Task 2 — `predictionTrend` horizon consistency | Task 2 — the shipped `factors` block + dead analytical predictor | The original question is answered by construction: `determineTrend` now indexes the same path at the same `horizonMinutes` as the chart. The live question is the whole five-term analytical model still running and serializing on every request. |
| Task 3 — `confidence` + COB/IOB | narrowed, and expanded | `ai/ContextAggregatorService:94-95` and `IsfMealWindowProfileService:245` now compute COB/IOB outside `activeCobIobInputs`; neither existed as a caller on 2026-08-08. `ContextAggregatorService` is patient-facing via `/api/ai-insights/retrospective`. |
| Task 4 — `ExperimentService` / `VerificationService` | unchanged in intent, line numbers refreshed | Still genuinely untraced. `ExperimentService:425` now delegates to `calculateGlucoseData`, which is worth confirming as delegation rather than duplication. |
| *(none)* | **Task 5 — `ContextAggregatorService`** | Not in the spec's §3 list (it post-dates the spec) and not in the original plan, but it reconstitutes the bug `30b76bd` fixed: `:109` computes a 2 h prediction from `carbRatio`/`isf`, rendered to the patient at `SafetyAndScoringService:53` and fed to the LLM at `LlmGatewayService:425,461`. Also carries a duplicate note converter (:142) and private copies of `DEFAULT_CARB_RATIO`, `DEFAULT_ISF`, `PRE_BOLUS_MAX_TIMING_EFFECT` and `calculatePreBolusTimingContribution`. Promoted out of Task 3, which now only surveys it. |
| *(none)* | **Task 6 — `BasalInsulinResolver`** | In the spec's §3 scope list, never given a task. Became a live dual-computation target when `49bcb7b` made `x3` an ODE state variable: `BasalInsulinResolver.suppressionCurve:87` and `HovorkaOdeSolver:388` now both answer "how suppressed is EGP", stitched at `HovorkaGlucosePredictionService:285-306` by overwriting `egp0` and zeroing `x3`. |
| *(none)* | **Task 7 — `DallaManGutModel`** | In the spec's §3 scope list, never given a task. The model class is a pure function library; the duplication is in its callers — `HovorkaOdeSolver:348-364`, the warm-up replay at `HovorkaGlucosePredictionService:586-598` (whose comment claims it is "identical to derivatives()"), and the display-only `ra` at `:443-444`. `HovorkaOdeSolver:349` uses a bare `1.68` where every other site uses `HALF_LIFE_TO_TMAX_G`. |
| *(none)* | **Task 8 — plasma insulin `I(t)`** | Surfaced by the reference map, in neither the spec nor any earlier plan version. `HovorkaOdeSolver:378` defers the `S₁→S₂→I` PK model and bridges an OpenAPS exponential IOB curve into Hovorka insulin action with `V_I_SCALE = 12.0` (:385). Two PK lineages, one magic constant, and the same absent compartment Task 6's EGP seam works around. |
| Task 5 — warm-up continuity test | Task 9, unchanged code, changed expectation | The 8-arg `buildPredictionPath` overload (:181) is unchanged, so the test compiles as written. But `62dd077` rewired the x3 bridge after the screenshot, so a pass no longer means the symptom never existed. |
| Task 6 — compile report | Task 10, added a by-quantity sweep, reachability re-verification, a spec-coverage table and a scope addendum | Direct response to how Task 1 went stale unnoticed. |
| "Tech Stack: Java 17" | Java 21 | `build.gradle:16` sets `JavaLanguageVersion.of(21)`; the original header was wrong when written. |
| finding template | added a `Reachability` line | Nothing in the original template forced a finding to state where it surfaces, which is exactly the claim that rotted. |
| — | **fragment-per-task instead of one shared findings file** | Nine tasks committed the same markdown file while three advertised themselves as parallel-safe, with "re-read it immediately before editing" as the only mitigation — which is not concurrency control. Under `subagent-driven-development`, which this plan's own header recommends, they would have conflicted or silently clobbered each other. Each task now owns one fragment; Task 11 concatenates back to the original path. |
| — | **added a Definition of done** | The plan had no completion criterion, so "done" was a judgment call by whoever stopped. Task 11 Step 9 now walks a checkable list. |
| *(none)* | **Task 10 — pinning tests** | Tasks 5, 6 and 7 each surface a duplication that agrees today with nothing enforcing it. A markdown finding does not stop the drift; this plan exists *because* a markdown finding rotted for 22 days. Test-only, no `src/main` changes. |
| — | **Task 11 opens a tracked issue per open finding, behind a confirmation gate** | The 2026-08-18 parameter audit ended with a table naming Plans 2–6 across ~20 findings; thirteen days later none of the five existed. Handing findings back as a conversation summary has a 0-for-5 record in this repo, so the findings now leave the docs tree and the report records their issue numbers. Issue creation is outward-facing and public, so Step 6 drafts a manifest and stops for explicit approval before Step 7 creates anything — a gate that stands even under a standing autonomy instruction. |
| — | added the reference map to Global Constraints | Advisory only: a quantity checklist so target selection stops depending on the spec's file list, which had already missed three live targets. Explicitly barred from being numeric ground truth — the document is LLM-generated (truncated `k_empt` LaTeX, uniform citation dates, a podcast in the bibliography, a "validation" section that validates nothing), and four of its constants conflict with the code in ways where the code is at least as likely to be right. |
| — | added a "duplications that currently agree" status convention | Tasks 6 and 7 both surface paths that compute the same number today with nothing enforcing it. Without a distinct status they read as either false alarms or live bugs, and they are neither. |

**Not carried forward:** the original Task 2's horizon question, and the original Task 1 Step 2's
instruction to confirm `determineTrend(factors, ...)` is called at "~line 135" — that call site
and that signature no longer exist.
