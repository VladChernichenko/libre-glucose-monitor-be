# Digital Twin - Machine-Learned Glucose Prediction

> Per-user machine learning that improves the physiological glucose forecast by comparing what the
> model **predicted** against what the CGM **actually** recorded, and personalising the model from
> that error. The result is a "digital twin" of each user's glucose response, plus a **probabilistic
> confidence band** around every prediction.

This document explains the whole system: the underlying mathematical model, the three learning
layers built on top of it, how they are wired into the live prediction path, how the model is
validated, and the design decisions (and caveats) that matter.

---

## 1. Background - the physiological prediction model

The prediction engine lives in `che.glucosemonitorbe.hovorka` and is an integrative Type-1 diabetes
model combining three published models:

| Model | Role | Key class |
|-------|------|-----------|
| **Hovorka (2004)** | Two-compartment glucose-insulin kinetics (the "brain"): plasma/tissue glucose, renal clearance, insulin action. | `HovorkaOdeSolver`, `HovorkaParameters` |
| **Dalla Man (2007)** | Three-compartment nonlinear gastrointestinal carb absorption (stomach -> gut -> blood). | `DallaManGutModel` |
| **Palumbo / macronutrient** | Macro-modulated gastric emptying (`tMaxG`), incretin (GLP-1) "ileal brake", fat/protein delay. | `MacroNutrientGastricModel` |

The solver integrates a 6-variable ODE system with **RK4 at 1-minute resolution** and emits a
prediction point every 5 min (0-4 h) / 10 min (4-8 h) as a `PredictionPointDTO`. Per-user parameters
(ISF, body weight, carb half-life, EGP) come from `HovorkaParameterService`.

This model is **deterministic**: given the same inputs it always produces the same single curve. Its
accuracy is fundamentally limited by two things:

1. **Population parameters vs. the individual.** Everyone's insulin sensitivity, carb absorption
   speed, and hepatic output differ, and drift over time.
2. **Noisy, incomplete logging.** Meals mis-estimated, insulin timing approximate, snacks forgotten.

The digital twin addresses (1) directly and is engineered to be robust to (2).

---

## 2. What the digital twin learns

Everything lives in two places:

- **`che.glucosemonitorbe.hovorka.learning`** - pure, dependency-free learning logic (unit-testable
  without Spring or a database).
- **`che.glucosemonitorbe.service.DigitalTwin*`** + `entity.UserDigitalTwin` + `scheduler` +
  `controller` - Spring wiring, persistence, scheduling, and the API.

There are **three learning layers**, fitted together during one calibration pass:

### Layer 1 - Physiological parameter scales (`TwinScales`)

Multiplicative corrections on the physiological parameters, centred on 1.0 (1.0 = no change):

- `isfScale` - corrects systematic insulin over/under-response.
- `agScale` - corrects systematic meal-magnitude (carb effect) bias.
- `tMaxGScale`, `egpScale` - **reserved** columns, not yet wired to the live ODE (see §7).

They are fitted by `DigitalTwinCalibrator` using **Nelder-Mead** optimisation of a **robust Huber
loss** (down-weights mis-logged outlier anchors) plus a **ridge-to-1.0 prior** (keeps the fit from
running to extreme values on thin/noisy data). The optimiser repeatedly replays the model over the
user's history via `PredictionReplayEngine` and scores each candidate scale set.

### Layer 2 - Residual bias grid (`ResidualBiasModel`)

A **24-slot hour-of-day** additive correction `[mmol/L]` learned from the error the calibrated
physiology *still* leaves behind - dawn phenomenon, an afternoon activity dip, a chronically
unlogged snack at a particular hour. Averaged over many days these show up as a stable bias at a
given clock hour.

Robustness comes from **empirical-Bayes shrinkage**: each hour's correction is pulled toward the
user's global mean residual (and the global mean toward zero) by a pseudo-count, so an hour with few
noisy samples barely moves off the pooled estimate while an hour with many consistent samples earns
its own value. Every correction is hard-clamped (±2.5 mmol/L).

### Layer 3 - Prediction uncertainty band (`PredictionUncertaintyModel`)

> *"Because of the probabilistic nature of prediction, we should have a band, not a single line."*

This layer learns the **standard deviation of the residual as a function of horizon** - how far off
the prediction typically is, 30 / 60 / 90 / 120 minutes out. That spread, rendered as a confidence
interval, is the band. It is measured on the residual left *after* the mean correction, so the band
is centred on the corrected prediction.

Design properties (all safety-oriented):

- **Per-horizon knots** at 30/60/90/120 min, with **variance shrinkage** toward the pooled variance
  so a sparse horizon bucket can't produce a freak-narrow (over-confident) or freak-wide band.
- **Sensor floor** (0.3 mmol/L): the band is never tighter than CGM noise.
- **Monotone widening**: σ is forced non-decreasing with horizon, so the band is an intuitive
  widening cone rather than dipping at a lucky horizon.
- **√-time extrapolation** beyond the last trained knot (the live path predicts to 4-8 h but
  calibration only trains to 2 h): a diffusion-like growth that keeps widening honestly without
  inventing data.
- **Cap** at 6.0 mmol/L.

Users without a personal fit yet get a **population-prior** band, so *every* prediction ships an
interval from day one.

---

## 3. How a calibration runs (data flow)

Orchestrated by `DigitalTwinCalibrationService.calibrateUser(userId)`:

```
 1. Load history          CGM readings (last 30 d) + notes (meals/boluses/basal)
        │
 2. Build raw predictor   HovorkaParameterService.buildRawForUser()  (NO twin overlay)
        │                  + PredictionResidualProvider.NONE          (raw model, no band)
        │
 3. Temporal split        earlier 80%  ->  TRAIN      later 20%  ->  VALIDATION (held out)
        │
 4. PredictionReplayEngine  open-loop replay at strided anchors: at each t0 feed the model the
        │                    inputs it would have had + the meals/insulin that actually happened,
        │                    compare predicted curve vs. real CGM  ->  AnchorSample{horizon,pred,actual}
        │
 5. DigitalTwinCalibrator
        ├- fit TwinScales               (Nelder-Mead + Huber + ridge, on TRAIN)
        ├- fit ResidualBiasModel        (on TRAIN residuals after scaling)
        ├- fit PredictionUncertaintyModel (on VALIDATION residuals - honest, out-of-sample σ)
        └- score baseline vs calibrated MAE on the held-out VALIDATION window
        │
 6. Persist UserDigitalTwin  scales + residual grid + uncertainty grid + diagnostics + applied flag
        │
 7. DigitalTwinService.invalidate(userId)   drop the 60 s hot-path cache
```

The whole thing is **open-loop replay** - the DB-driven, main-source counterpart of the offline
`BacktestHarness` test tool.

### The safety gate

The twin's `applied` flag is set **only if the calibrated model beats the un-calibrated model on the
held-out validation window** (by a minimum margin, with a minimum sample count). If the fit doesn't
generalise - e.g. because the training window was dominated by mis-logged data - the record is
stored with `applied = false` and **predictions are left completely unchanged**. This is what lets
the system learn aggressively without ever shipping a regression.

---

## 4. How the twin is applied - *predictions only*

**A deliberate, explicit product decision: the twin never touches insulin-dosing settings.** The
user's `user_settings.isf` / `carb_ratio` (used by the bolus calculator) are never written. The twin
affects only the prediction curve.

The overlay happens at three points, all reading from `DigitalTwinService` (which serves an *active*
twin only when `applied = true`, and is gated by the `digital-twin-enabled` feature flag):

| What | Where | Source |
|------|-------|--------|
| Parameter scales | `HovorkaParameterService.buildForUser()` applies scales; `buildRawForUser()` skips them (used by calibration) | `DigitalTwinService.activeScales()` |
| Residual bias | Added to each emitted point in `HovorkaGlucosePredictionService` | `PredictionResidualProvider.residualMmol()` |
| Uncertainty band | Lower/upper bounds computed per emitted point | `PredictionResidualProvider.uncertaintySdMmol()` |

During calibration replay the injected provider is `PredictionResidualProvider.NONE`, so residuals
are always measured against the **raw** physiological model and never double-corrected.

### The band on the wire

Each `PredictionPointDTO` now carries four additional fields (all null when no band applies, e.g.
during raw replay):

```java
Double predictedGlucoseLower;   // predictedGlucose − z*σ(horizon)
Double predictedGlucoseUpper;   // predictedGlucose + z*σ(horizon)
Double uncertaintySd;           // σ at this horizon [mmol/L]
Double confidenceLevel;         // 0.90  (z = 1.6449, two-sided normal)
```

The confidence level is currently a fixed **90%** interval (constant in
`HovorkaGlucosePredictionService`). The band assumes an approximately Gaussian residual.

> **Frontend note:** the backend now returns the bounds; the iPhone/watch chart still needs to
> render the shaded region between `predictedGlucoseLower` and `predictedGlucoseUpper`.

---

## 5. Persistence

One row per user in **`user_digital_twin`** (`src/main/resources/db/migration/V8__user_digital_twin.sql`):

| Column | Meaning |
|--------|---------|
| `isf_scale`, `ag_scale` | Active physiological scales (1.0 = neutral). |
| `tmax_g_scale`, `egp_scale` | Reserved (not yet wired to the live ODE). |
| `residual_grid` | 24 comma-separated per-hour corrections `[mmol/L]`. |
| `uncertainty_sd_grid` | Per-horizon σ `[mmol/L]` at 30/60/90/120 min (band). |
| `applied` | TRUE ⇢ beat baseline out-of-sample and active for predictions. |
| `mae_baseline`, `mae_calibrated`, `improvement_pct` | Out-of-sample fit diagnostics. |
| `train_samples`, `val_samples`, `confidence`, `status`, `fitted_at` | Observability. |

> **Schema rule:** the column set is owned entirely by `V8__...`. Per project convention, changes to
> this table are made by **editing `V8`**, not by adding an `ALTER TABLE` migration (assumes a
> clean-DB dev workflow; a populated DB needs `flyway repair` after a checksum change).

`DigitalTwinService` caches the active twin for **60 s**, so the per-point residual/σ lookup never
hits the database on the prediction hot path; `invalidate(userId)` is called right after each
recalibration.

---

## 6. Configuration, scheduling & API

**Feature flag** (`application.yml` -> `app.features.digital-twin-enabled`):

```yaml
app:
  features:
    digital-twin-enabled: ${APP_FEATURES_DIGITAL_TWIN_ENABLED:true}
```

When disabled, `DigitalTwinService` resolves everything to neutral and predictions are identical to
the raw model.

**Scheduling.** `DigitalTwinCalibrationScheduler.recalibrateAll()` is annotated
`@Scheduled(cron = "${app.digital-twin.cron:0 15 3 * * *}")` (03:15 daily). ⚠️ The repository does
**not** currently declare `@EnableScheduling`, so - as with the other schedulers here - the cron does
not fire on its own; the reliable trigger is the manual endpoint.

**API** (`DigitalTwinController`, base `/api/digital-twin`):

| Method | Path | Purpose |
|--------|------|---------|
| `GET`  | `/api/digital-twin` | Current twin status/diagnostics for the user (`DigitalTwinStatusDTO`). |
| `POST` | `/api/digital-twin/recalibrate` | Recalibrate this user now and persist the outcome. |

---

## 7. Validation on real-world data (AZT1D 2025)

The learning was validated against the **AZT1D 2025** public dataset - 25 real Type-1 subjects on
automated insulin delivery (AID) pumps, 5-minute CGM with carbs, boluses, and auto-basal.

- **Loader + harness:** `src/test/java/che/glucosemonitorbe/azt1d/` - `Azt1dDataset` (header-driven,
  handles the two column layouts present in the dataset) and `Azt1dCalibrationValidationTest`
  (data-gated: skips cleanly in CI, runs when the dataset path is supplied).
- **Protocol:** per subject, fit the twin on the earlier 80% and score on the held-out later 20%.

**Result:**

```
Subjects scored: 25    |    twin applied (beat baseline out-of-sample): 23
Mean baseline MAE:   2.39 mmol/L
Mean effective MAE:  1.73 mmol/L      ->  ~28% reduction
```

The 2 subjects where calibration didn't generalise were correctly **not** applied (safety gate). The
learned band widens with horizon exactly as intended:

| horizon | 30 min | 60 min | 90 min | 120 min |
|---------|--------|--------|--------|---------|
| σ (mmol/L) | 1.64 | 2.14 | 2.50 | 2.80 |
| **±90% band** | ±2.69 | ±3.52 | ±4.12 | **±4.60** |

Run it:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew test --tests '*Azt1dCalibrationValidationTest' \
  -Dazt1d.dir="/path/to/AZT1D 2025"
```

---

## 8. Design decisions & caveats

**Why these choices (so they aren't "fixed" wrongly later):**

- **Robustness is the core requirement.** Real logging is unreliable (forgotten/mis-timed meals and
  insulin). Hence: Huber loss, ridge-to-prior, empirical-Bayes shrinkage on both the residual grid
  and the band variance, and the **temporal-holdout safety gate**.
- **Predictions only.** Auto-applying learned parameters to dosing settings would be a safety
  hazard; the twin improves forecasts silently and never changes dosing advice.
- **`tMaxGScale` / `egpScale` reserved.** `tMaxG` is already overridden per-meal by
  `MacroNutrientGastricModel` in the `/predict` path (a global scale wouldn't survive), and
  fasting/EGP drift is absorbed more robustly by the residual layer. The columns exist for future
  use.
- **Hour-of-day residual assumes a UTC server.** Calibration derives clock hours from CGM epochs
  (UTC) and note timestamps via `ZoneOffset.UTC`; live prediction uses `LocalDateTime.now()`. These
  align only when the server runs UTC (the codebase's existing convention). Revisit if per-user
  timezones are introduced.

**Honest limitations of the AZT1D numbers:**

1. **Population prior mismatch.** Many fits railed to the scale clamps (e.g. `isf×0.50`, `aG×2.00`)
   because the validation used a population base ISF (2.2) rather than each subject's measured ISF.
   In the live app the base ISF comes from the user's own experiments, so scales sit more centred and
   the calibration does less "compensating for a bad prior."
2. **Auto-basal treated as steady-state background.** AID basal micro-boluses are not injected as
   insulin; the residual layer absorbs their average time-of-day effect, so it carries much of the
   gain on this particular dataset.
3. **Open-loop replay overstates prospective accuracy.** The harness feeds each subject's *actual*
   future meals/insulin (it measures the physiological model + calibration, not the harder problem of
   forecasting the user's own future behaviour) - the same caveat as `BacktestHarness`.

---

## 9. File map

```
src/main/java/che/glucosemonitorbe/
├-- hovorka/
│   ├-- HovorkaGlucosePredictionService.java   # emits points + residual correction + band
│   ├-- HovorkaParameterService.java           # buildForUser (twin overlay) / buildRawForUser
│   ├-- HovorkaOdeSolver.java, DallaManGutModel.java, MacroNutrientGastricModel.java
│   └-- learning/
│       ├-- PredictionReplayEngine.java         # open-loop replay -> AnchorSample
│       ├-- DigitalTwinCalibrator.java          # fits all 3 layers + scores + safety gate
│       ├-- TwinScales.java                     # Layer 1: physiological scales
│       ├-- ResidualBiasModel.java              # Layer 2: hour-of-day residual grid
│       ├-- PredictionUncertaintyModel.java     # Layer 3: per-horizon σ -> band
│       ├-- RobustLoss.java, NelderMead.java     # optimiser + Huber/ridge objective
│       └-- PredictionResidualProvider.java     # residual + σ hook into the live path
├-- service/
│   ├-- DigitalTwinCalibrationService.java      # orchestration + persistence
│   ├-- DigitalTwinService.java                 # read/apply active twin (cached)
│   └-- DigitalTwinResidualProvider.java        # @Primary provider for the live path
├-- entity/UserDigitalTwin.java
├-- repository/UserDigitalTwinRepository.java
├-- scheduler/DigitalTwinCalibrationScheduler.java
├-- controller/DigitalTwinController.java
└-- dto/PredictionPointDTO.java                 # + band fields; DigitalTwinStatusDTO

src/main/resources/db/migration/V8__user_digital_twin.sql
src/test/java/che/glucosemonitorbe/
├-- hovorka/learning/*Test.java                 # unit tests for each layer
└-- azt1d/                                       # AZT1D loader + real-data validation harness
```

---

## 10. Build & run notes

- **Gradle requires Java 21** here. The machine's default `java_home` may be Java 25, which makes a
  fresh Gradle daemon fail with *"Unsupported class file major version 69"*. Always:

  ```bash
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
  ```

- Run the learning unit tests:

  ```bash
  ./gradlew test --tests 'che.glucosemonitorbe.hovorka.learning.*'
  ```

- The AZT1D validation and the offline `BacktestHarness` are **data-gated**: they skip unless you
  pass the dataset path (`-Dazt1d.dir=...` / `-Dbacktest.cgm=...`), which `build.gradle` forwards to the
  test JVM.

---

## 11. Future work

- Render the band in the iPhone/watch UI (shaded region between lower/upper).
- Make the confidence level configurable (50 / 80 / 90%) per request or per user.
- Wire `tMaxGScale` / `egpScale` once identifiable, and add an activity signal (AZT1D `DeviceMode`
  = exercise is a natural feature) to the residual/uncertainty layers.
- Seed each user's base ISF/weight from their experiments before calibrating (keeps scales off the
  clamps).
