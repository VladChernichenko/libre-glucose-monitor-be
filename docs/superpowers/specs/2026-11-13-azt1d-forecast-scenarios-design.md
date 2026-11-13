# Forecast scenarios from the AZT1D subjects: Design

**Date:** 2026-11-13
**Status:** Draft, awaiting approval
**Repo:** `glucose-monitor-be` (test code and test resources only; no production code changes)
**Context:** on 2026-11-13 the dashboard forecast stayed flat after a 4 U dose. The twin's ISF scale was pinned at its 0.5 floor by a stressed evening that the nightly fit had counted as ordinary data. The existing AZT1D tests report average error per subject, which a pinned scale barely moves, so nothing would have flagged it.

---

## 1. Intent

Turn the 25 real AZT1D subjects into named, checkable situations ("scenarios"): one real episode, what the forecast said, what actually happened, and a pass/fail rule. A regression like the flat forecast then fails a specific scenario by name instead of hiding inside an average.

## 2. Goals

1. A **miner** scans all 25 subjects and finds episodes of each scenario kind by rule, and prints a ranked report: which subjects and times qualify, and how the forecast did on each.
2. A **scenario suite** replays the forecast on each mined episode and checks the kind's rule. It runs over the whole dataset on a machine that has it, and is skipped when the dataset is absent, like the existing AZT1D tests.
3. A **small curated set** (target 20 to 30 episodes) is committed as de-identified fixtures and run as ordinary tests that always run, with no dataset needed.
4. Today's behaviour is recorded as a baseline so future runs fail only on a **regression**, not on gaps that already exist.

## 3. Non-goals

- Changing the model, the twin or any production code. This piece only catches; fixing what it finds is separate work.
- Clinical validation, dosing advice or accuracy claims about the model.
- Scenarios from the owner's own notes (the dev user). Only the AZT1D subjects.
- Running the 25-subject sweep in CI.

## 4. Anti-goals

- A suite that fails on day one for known reasons, so people learn to ignore it.
- Committed fixtures that identify a person or carry a raw patient export.
- Pass/fail rules tuned until everything passes, which would hide real problems.

## 5. Constraints

- **The pumps are automated.** All subjects use Tandem Control-IQ, which adds automatic boluses and adjusts basal itself. A "human-chosen dose" scenario must exclude windows with automatic boluses nearby. Bolus types in the data: `Standard` (meal), `Standard/Correction`, `Automatic Bolus/Correction`, `BLE Standard Bolus`, `Extended`, `Quick`.
- **Only CGM, insulin, carbs and device mode exist.** No activity notes, no unlogged-event labels, no notes text.
- **Per subject 29 to 49 days** of 5-minute glucose, so a subject offers tens of episodes, not hundreds.
- **Patient-data guard:** `NoPatientPhiDumpsInRepoTest` blocks committing patient exports. Fixtures must be small, de-identified derivatives and the guard must be extended to recognise them explicitly.
- **Dataset terms are unconfirmed.** The dataset is public (IRB-approved, Mayo Clinic Arizona, cite the paper), but its redistribution licence is not in the files here. Committing fixtures is **gated on the owner confirming the terms allow it**.
- The existing tooling to build on: `Azt1dDataset` (loader and per-subject profile estimate), `PredictionReplayEngine` and `AnchorSample` (replay with predicted vs actual), the `rawPredictor()` helper in `Azt1dCalibrationValidationTest`, and the existing committed fixture precedent in `src/test/resources/twin/`.

## 6. Approaches considered

1. **Data-gated suite only.** Everything reads the local dataset. No licence or privacy question, but it only runs on the owner's machine, so a regression is found only when someone runs it. Rejected as the end state.
2. **Committed fixtures only.** Always runs, but depends on the licence answer and on a hand-picked sample, with no way to see how common a problem is across all 25. Rejected as the start.
3. **Both (chosen by the owner).** The miner finds and ranks episodes across everyone; only a curated few become fixtures. The full sweep stays data-gated.

## 7. Decisions

Decided by the owner:
- Approach 3.

My calls (correct any of them):
- **Catch only.** Scenarios that fail today are recorded as known gaps, not fixed here.
- **Mine only human-chosen doses**, skipping windows with an automatic bolus within 2 hours of the episode.
- **Scenario kinds, first set:**
  1. *Correction drop:* a correction dose with no carbs from a start of at least 9 mmol/L. Rule: the 2-hour forecast falls, in the same direction as reality when reality fell by at least 1 mmol/L, and its error stays within a tolerance.
  2. *Meal with bolus:* at least 30 g of carbs with a manual bolus within 15 minutes. Rule: the forecast shows the post-meal rise and its peak time lands within a tolerance of the real peak.
  3. *Meal without bolus:* at least 30 g of carbs and no manual bolus for 3 hours. Rule: the forecast rises.
  4. *Overnight steady:* 00:00 to 05:00, no manual insulin or carbs. Rule: the 2-hour forecast stays within a band around the start.
  5. *Twin sanity:* per subject, whether the fitted ISF and meal scales sit at a clamp limit. Reported per subject and checked only as "no more subjects at a limit than the baseline".
- **Which model is checked:** the forecast a user would see, meaning the fitted twin applied. The fit for an episode uses only data before it, so the test never trains on its own answer. The neutral model is reported alongside.
- **Pass/fail rules are direction plus a tolerance, with a baseline.** The first run records each scenario's error. After that a scenario fails when it is worse than its baseline by a set margin. Tolerances come from that first run and are listed in the report; they are not tuned to make scenarios pass.
- **Fixtures:** one small file per scenario holding glucose and events with times relative to the episode start, no dates and no subject ids, plus the scenario kind and the recorded expectation. A subject profile is stored as numbers only.
- **Guard:** a test that fixtures contain no absolute dates or subject ids and stay under a total size cap (300 KB); the PHI guard gets an explicit allow-list for the fixture folder.

### Calls added after the builder check (mine; correct any)

- **Refit cadence:** the twin is refitted once per subject per simulated day, using only data before that day, the way the nightly job runs. A single fit per subject would not reproduce the incident (one bad evening contaminating a nightly fit). A test-side wrapper does the date cut, so there are no production code changes. Cost is bounded by sampling days (default every 3rd day) rather than fitting every day.
- **Showing the pinned-twin bug is caught:** a meta-test injects a pinned `TwinScales` (isf at the floor) into a subject's replay and asserts that the correction-drop and twin-sanity checks turn red. Real AZT1D data may never produce that state on its own, so the suite does not rely on it.
- **A scenario that already fails:** the baseline stores both the rule outcome and the error. A scenario whose rule fails today is listed explicitly as a known gap (visible in the report, not silent) and stays out of the regression check until it passes, after which it becomes required. A forecast that does not move after a dose is a hard rule, never only a drift from baseline.
- **Baseline keys:** the data-gated sweep keeps its baseline per scenario kind and subject, as the median error, so changing the mining thresholds does not break it. Each committed fixture carries its own expectation. The sweep baseline is a local file, not committed.
- **Fixture schema:** times relative to the episode start plus the clock hour (0 to 23) and weekday, which the model needs and which do not identify anyone. The fitted twin scales and the subject profile are stored as numbers, so the always-run tests exercise the forecast and not the fit; the fit is covered by the data-gated sweep.
- **Horizon:** the suite replays out to 180 minutes so a meal peak is not cut off at the usual 120. Peak time is taken from a lightly smoothed trace and meal episodes with a second bolus or meal inside the window are skipped.
- **Automatic-insulin exclusion:** applied from 2 hours before the episode to the end of its checked horizon. Control-IQ's continuous basal changes cannot be separated from the data, so the overnight kind accepts them as the delivered basal the data shows.

## 7a. Open question for the owner

Does the AZT1D licence allow redistributing de-identified excerpts in this repo (which is on a public GitHub remote)? Until that is confirmed, the build produces fixtures into an ignored local folder only, and committing them is a later step.

## 8. Left to the builder

- Exact episode-selection thresholds beyond those above, and how many episodes per kind and subject are kept.
- The tolerance values and the regression margin, derived from the first baseline run.
- File format of the baseline and fixtures (JSON like the existing `twin/` fixtures is the default).
- How the miner's ranked report is printed and where its output goes.
- Whether the fit-on-earlier-data step reuses `DigitalTwinCalibrator` directly or a lighter path, as long as there is no leakage.

## 9. Success

- Run against your local copy of the dataset, the miner and the suite finish and print a report of episodes per kind and per subject, with the forecast's error on each.
- The suite would have failed on a twin pinned at its floor: a scenario or the twin-sanity check turns red when `TwinScales.MIN_SCALE` is the fitted value for a subject whose data does not justify it.
- With no dataset present, the committed fixtures (once the licence is confirmed) run as ordinary tests in under 30 seconds.
