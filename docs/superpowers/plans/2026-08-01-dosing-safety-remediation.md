# Dosing Safety Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close four patient-harm defects in the bolus calculator, the glucose-calculations data path, and the carb-ratio titrator, replacing fabricated fallback values with explicit refusals.

**Architecture:** A new `DosingRefusedException` carrying a typed `DosingRefusalReason` is thrown by `InsulinCalculatorService` whenever a dose cannot be safely computed, and mapped to HTTP 422 by the existing `GlobalExceptionHandler`. Input bounds move onto the request DTO as Jakarta Bean Validation constraints. `GlucoseCalculationsService` stops swallowing repository exceptions so a database fault surfaces as 503 instead of a fabricated zero. `VerificationService` gains two eligibility checks that keep hypoglycemia-contaminated meals out of carb-ratio titration.

**Tech Stack:** Java 21, Spring Boot 3, Spring Data JPA, Lombok, Jakarta Bean Validation (`spring-boot-starter-validation`, already on the classpath), JUnit 5, Mockito, AssertJ, Testcontainers (PostgreSQL 16).

## Global Constraints

- **Spec:** `docs/superpowers/specs/2026-08-01-dosing-safety-remediation-design.md`. Read it before starting.
- **Build requires Java 21.** Export before any Gradle command: `export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home`. The machine default is Java 25 and a fresh daemon on it fails with "Unsupported class file major version 69".
- **No schema changes.** `V1__baseline_schema.sql` is not to be edited. `carb_ratio` and `isf` stay `NOT NULL DEFAULT 2.0 / 1.0`.
- **No client changes.** The web and iOS repos are out of scope.
- **Physiological constants**, exact values, used verbatim:
  - insulin:carb ratio envelope `3.0`–`30.0` g/U
  - hypoglycemia refusal threshold `3.9` mmol/L (ADA/ATTD Level 1)
  - maximum bolus `0.3` U/kg, body-weight fallback `70.0` kg
  - glucose plausibility ceiling `33.0` mmol/L, floor `1.0` mmol/L
  - target glucose band `4.0`–`10.0` mmol/L
  - carbs `0.0`–`300.0` g, active insulin `0.0`–`50.0` U
- **`carb_ratio` is a glucose-rise coefficient** (mmol/L per 10 g), *not* an insulin:carb ratio. Never treat it as g/U.
- Every task ends green: `./gradlew test` must pass before the commit step.

### Deviation from the spec, applied throughout

The spec's §3.5 illustrates the refusal body as `{ "reason": ..., "message": ..., "backendMode": true }`. This plan instead reuses the existing `CustomErrorResponse` record (`status`, `error`, `message`, `path`, `timestamp`) with the reason code carried in `error`. Rationale: the codebase already has exactly one API error contract, served by `GlobalExceptionHandler`; introducing a second shape for one endpoint would leave clients parsing two formats. The reason code is still machine-readable, which is what §3.5 requires.

---

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `src/main/java/che/glucosemonitorbe/exception/DosingRefusalReason.java` | Enum of refusal reasons + patient-safe message per reason |
| `src/main/java/che/glucosemonitorbe/exception/DosingRefusedException.java` | Runtime exception carrying a reason and an internal detail string |
| `src/test/java/che/glucosemonitorbe/exception/GlobalExceptionHandlerDosingTest.java` | Unit test for the 422 and 503 mappings |
| `src/test/java/che/glucosemonitorbe/service/VerificationServiceTest.java` | Unit test for H1 eligibility |

**Modified**

| File | Change |
|---|---|
| `exception/GlobalExceptionHandler.java` | `DosingRefusedException` → 422; `DataAccessException` → 503 |
| `service/InsulinCalculatorService.java` | Derive g/U; hypo refusal; signed correction; max-bolus ceiling; drop `resolveIsf` / `DEFAULT_ISF` |
| `dto/InsulinCalculationRequest.java` | Bean Validation constraints |
| `controller/InsulinCalculatorController.java` | `@Valid`; local 422 mapping for validation failures; drop blanket `catch` |
| `service/GlucoseCalculationsService.java` | Remove the two swallow-and-return-empty blocks |
| `service/VerificationService.java` | Two new eligibility checks |
| `src/test/.../service/InsulinCalculatorServiceTest.java` | New cases |
| `src/test/.../controller/InsulinCalculatorControllerTest.java` | MockMvc cases for validation |
| `src/test/.../integration/InsulinCalculatorIntegrationTest.java` | Reconcile with new dose arithmetic |

---

### Task 1: Refusal vocabulary and HTTP mapping

**Files:**
- Create: `src/main/java/che/glucosemonitorbe/exception/DosingRefusalReason.java`
- Create: `src/main/java/che/glucosemonitorbe/exception/DosingRefusedException.java`
- Modify: `src/main/java/che/glucosemonitorbe/exception/GlobalExceptionHandler.java`
- Test: `src/test/java/che/glucosemonitorbe/exception/GlobalExceptionHandlerDosingTest.java`

**Interfaces:**
- Consumes: nothing — this is the foundation task.
- Produces: `DosingRefusalReason` (enum constants `INVALID_INPUT`, `SETTINGS_INVALID`, `INSULIN_PARAMS_INCONSISTENT`, `GLUCOSE_BELOW_SAFE_THRESHOLD`, `GLUCOSE_IMPLAUSIBLE_UNIT`, `DOSE_EXCEEDS_MAX_BOLUS`, each with `String getMessage()`); `DosingRefusedException(DosingRefusalReason reason, String detail)` with `DosingRefusalReason getReason()` and `String getDetail()`. Tasks 2–5 throw this.

> **Why a distinct exception rather than `ResponseStatusException`:** `GlobalExceptionHandler` already maps `RuntimeException` to 500. Spring picks the most specific `@ExceptionHandler`, so a dedicated type is what keeps a refusal from being logged and reported as a server fault.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/exception/GlobalExceptionHandlerDosingTest.java`:

```java
package che.glucosemonitorbe.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerDosingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private HttpServletRequest requestFor(String uri) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(uri);
        return request;
    }

    @Test
    void dosingRefusal_maps_to_422_with_reason_code_in_error_field() {
        DosingRefusedException ex = new DosingRefusedException(
                DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD, "glucose=3.0");

        ResponseEntity<CustomErrorResponse> response =
                handler.handleDosingRefused(ex, requestFor("/api/insulin/calculate"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError())
                .isEqualTo("GLUCOSE_BELOW_SAFE_THRESHOLD");
        assertThat(response.getBody().getMessage())
                .isEqualTo(DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD.getMessage());
        assertThat(response.getBody().getPath()).isEqualTo("/api/insulin/calculate");
    }

    @Test
    void refusal_body_never_leaks_the_internal_detail_string() {
        DosingRefusedException ex = new DosingRefusedException(
                DosingRefusalReason.SETTINGS_INVALID, "isf=null carbRatio=2.0 userId=abc");

        ResponseEntity<CustomErrorResponse> response =
                handler.handleDosingRefused(ex, requestFor("/api/insulin/calculate"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).doesNotContain("userId=abc");
    }

    @Test
    void dataAccessFailure_maps_to_503() {
        DataAccessResourceFailureException ex =
                new DataAccessResourceFailureException("connection refused");

        ResponseEntity<CustomErrorResponse> response =
                handler.handleDataAccess(ex, requestFor("/api/glucose-calculations/"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).doesNotContain("connection refused");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew test --tests '*GlobalExceptionHandlerDosingTest'
```

Expected: compilation failure — `DosingRefusedException`, `DosingRefusalReason`, `handleDosingRefused`, and `handleDataAccess` do not exist.

- [ ] **Step 3: Create the reason enum**

Create `src/main/java/che/glucosemonitorbe/exception/DosingRefusalReason.java`:

```java
package che.glucosemonitorbe.exception;

/**
 * Why a dose calculation was refused. The message is patient-facing and must never
 * contain internal state - the {@code detail} on {@link DosingRefusedException} carries
 * that, and is logged rather than returned.
 */
public enum DosingRefusalReason {

    INVALID_INPUT(
            "The dose request was incomplete or outside the accepted range."),

    SETTINGS_INVALID(
            "Insulin sensitivity and carb ratio must be configured before a dose can be calculated."),

    INSULIN_PARAMS_INCONSISTENT(
            "The configured insulin sensitivity and carb ratio are inconsistent. "
                    + "Please review these settings with your clinician."),

    GLUCOSE_BELOW_SAFE_THRESHOLD(
            "Glucose is below the safe threshold for bolusing. Treat the low first."),

    GLUCOSE_IMPLAUSIBLE_UNIT(
            "Glucose is outside the physiological range for mmol/L. Check that the value is not in mg/dL."),

    DOSE_EXCEEDS_MAX_BOLUS(
            "The calculated dose exceeds the maximum safe bolus. Please check the inputs.");

    private final String message;

    DosingRefusalReason(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
```

- [ ] **Step 4: Create the exception**

Create `src/main/java/che/glucosemonitorbe/exception/DosingRefusedException.java`:

```java
package che.glucosemonitorbe.exception;

/**
 * Thrown when a dose cannot be computed safely. Maps to HTTP 422.
 *
 * <p>This is deliberately <em>not</em> a fallback: the calculator refuses rather than
 * substituting a plausible number, because a fabricated dose is indistinguishable from a
 * real recommendation once it reaches the patient.
 */
public class DosingRefusedException extends RuntimeException {

    private final DosingRefusalReason reason;
    private final String detail;

    public DosingRefusedException(DosingRefusalReason reason, String detail) {
        super(reason.name() + ": " + detail);
        this.reason = reason;
        this.detail = detail;
    }

    public DosingRefusalReason getReason() {
        return reason;
    }

    /** Internal diagnostic state. Logged server-side, never returned to the caller. */
    public String getDetail() {
        return detail;
    }
}
```

- [ ] **Step 5: Add both handlers to GlobalExceptionHandler**

In `src/main/java/che/glucosemonitorbe/exception/GlobalExceptionHandler.java`, add this import alongside the existing ones:

```java
import org.springframework.dao.DataAccessException;
```

Then insert both methods immediately after the existing `handleAccessDenied` method, inside the `// -- 4xx: client-actionable` section:

```java
    /**
     * A dose could not be computed safely. 422 rather than 400: the request was well-formed,
     * but acting on it would be clinically unsafe. The reason code goes in {@code error} so
     * clients can branch on it; the internal detail is logged, not returned.
     */
    @ExceptionHandler(DosingRefusedException.class)
    public ResponseEntity<CustomErrorResponse> handleDosingRefused(
            DosingRefusedException ex, HttpServletRequest request) {
        log.info("Dosing refused on {} [{}]: reason={} detail={}",
                request.getRequestURI(), correlationId(), ex.getReason(), ex.getDetail());
        return build(HttpStatus.UNPROCESSABLE_ENTITY, ex.getReason().name(),
                ex.getReason().getMessage(), request);
    }

    /**
     * Database unreachable or failing. 503, not 500: it is transient and the client should
     * retry rather than treat the response as data. Safety-critical readings (IOB, COB) must
     * never degrade to a fabricated zero on this path.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<CustomErrorResponse> handleDataAccess(
            DataAccessException ex, HttpServletRequest request) {
        log.error("Data access failure on {} [{}]", request.getRequestURI(), correlationId(), ex);
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                "Your data is temporarily unavailable. Please try again shortly.", request);
    }
```

- [ ] **Step 6: Run the test to verify it passes**

```bash
./gradlew test --tests '*GlobalExceptionHandlerDosingTest'
```

Expected: 3 tests PASS.

- [ ] **Step 7: Run the full suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. `DataAccessException` → 503 is new behaviour for every endpoint; if a pre-existing test asserted 500 on a DB failure, update it to 503 and note it in the commit body.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/exception/ src/test/java/che/glucosemonitorbe/exception/
git commit -m "feat: typed dosing refusals mapped to 422, DB faults to 503"
```

---

### Task 2: C1 — derive the insulin:carb ratio

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java:20` (remove `DEFAULT_ISF`), `:210-246` (replace `resolveIsf` and `calculateRecommendedInsulin`)
- Test: `src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceTest.java`

**Interfaces:**
- Consumes: `DosingRefusedException`, `DosingRefusalReason.{SETTINGS_INVALID, INSULIN_PARAMS_INCONSISTENT, INVALID_INPUT}` from Task 1.
- Produces: `double resolveGramsPerUnit(UserSettingsDTO settings)` and `UserSettingsDTO requireSettings(String userIdStr)`, both private. `calculateRecommendedInsulin` keeps its existing public signature: `InsulinCalculationResponse calculateRecommendedInsulin(InsulinCalculationRequest request)`. Tasks 3 and 4 extend the same method.

> **The arithmetic:** `carbRatio` is mmol/L per 10 g and `ISF` is mmol/L per U, so `gramsPerUnit = 10 × ISF / carbRatio`. At population values (ISF 2.2, carbRatio 2.0) that is 11 g/U — which is why the hardcoded 12 looked reasonable on average and was wrong for every individual.

- [ ] **Step 1: Write the failing tests**

Append to `src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceTest.java`, inside the class. Add these imports at the top of the file:

```java
import che.glucosemonitorbe.dto.InsulinCalculationRequest;
import che.glucosemonitorbe.dto.InsulinCalculationResponse;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

Then add:

```java
    // -- C1: derived insulin:carb ratio ----------------------------------------

    /** Settings stub: 7-arg constructor is (id, userId, carbRatio, isf, halfLife, maxCob, bodyWeightKg). */
    private void givenSettings(Double carbRatio, Double isf, Double bodyWeightKg) {
        UserSettingsDTO settings = new UserSettingsDTO(
                UUID.randomUUID(), USER_ID, carbRatio, isf, 45, 240, bodyWeightKg);
        when(userSettingsService.getUserSettings(any(UUID.class))).thenReturn(settings);
    }

    private InsulinCalculationRequest doseRequest(double carbs, double currentGlucose, double targetGlucose) {
        return InsulinCalculationRequest.builder()
                .carbs(carbs)
                .currentGlucose(currentGlucose)
                .targetGlucose(targetGlucose)
                .activeInsulin(0.0)
                .userId(USER_ID.toString())
                .build();
    }

    @Test
    void mealDose_usesRatioDerivedFromIsfAndCarbRatio_notTheOldHardcoded12() {
        // carbRatio 2.0 mmol/L per 10 g, ISF 2.2 mmol/L/U -> 10 * 2.2 / 2.0 = 11.0 g/U
        givenSettings(2.0, 2.2, 70.0);

        // No correction: current == target. 55 g / 11 g/U = 5.0 U
        InsulinCalculationResponse response =
                service.calculateRecommendedInsulin(doseRequest(55.0, 6.0, 6.0));

        assertThat(response.getRecommendedInsulin()).isEqualTo(5.0);
    }

    @Test
    void insulinSensitiveUser_getsSmallerMealDoseThanTheOldConstant() {
        // carbRatio 1.0, ISF 2.5 -> 25 g/U. 50 g -> 2.0 U (the old code gave 50/12 = 4.17 U).
        givenSettings(1.0, 2.5, 70.0);

        InsulinCalculationResponse response =
                service.calculateRecommendedInsulin(doseRequest(50.0, 6.0, 6.0));

        assertThat(response.getRecommendedInsulin()).isEqualTo(2.0);
    }

    @Test
    void derivedRatioBelowEnvelope_refuses_ratherThanClamping() {
        // carbRatio 8.0, ISF 1.0 -> 1.25 g/U, below the 3.0 floor
        givenSettings(8.0, 1.0, 70.0);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(50.0, 6.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(ex -> ((DosingRefusedException) ex).getReason())
                .isEqualTo(DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT);
    }

    @Test
    void derivedRatioAboveEnvelope_refuses() {
        // carbRatio 0.5, ISF 4.0 -> 80 g/U, above the 30.0 ceiling
        givenSettings(0.5, 4.0, 70.0);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(50.0, 6.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(ex -> ((DosingRefusedException) ex).getReason())
                .isEqualTo(DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT);
    }

    @Test
    void nonPositiveOrMissingSettings_refuse() {
        givenSettings(2.0, 0.0, 70.0);
        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(50.0, 6.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(ex -> ((DosingRefusedException) ex).getReason())
                .isEqualTo(DosingRefusalReason.SETTINGS_INVALID);

        givenSettings(2.0, null, 70.0);
        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(50.0, 6.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(ex -> ((DosingRefusedException) ex).getReason())
                .isEqualTo(DosingRefusalReason.SETTINGS_INVALID);
    }

    @Test
    void missingCarbsOrGlucose_refusesAsInvalidInput() {
        givenSettings(2.0, 2.2, 70.0);
        InsulinCalculationRequest noCarbs = InsulinCalculationRequest.builder()
                .currentGlucose(6.0).targetGlucose(6.0).userId(USER_ID.toString()).build();

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(noCarbs))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(ex -> ((DosingRefusedException) ex).getReason())
                .isEqualTo(DosingRefusalReason.INVALID_INPUT);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew test --tests '*InsulinCalculatorServiceTest'
```

Expected: the new tests FAIL. `mealDose_usesRatioDerivedFromIsfAndCarbRatio_notTheOldHardcoded12` fails with `expected 5.0 but was 4.58` (55/12), confirming the hardcoded constant is live.

- [ ] **Step 3: Replace the constants and the calculation**

In `src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java`:

Delete the `DEFAULT_ISF` field (line 20) and its Javadoc. Add these constants next to `DEFAULT_DIA_HOURS`:

```java
    /** Physiological envelope for the derived insulin:carb ratio, grams per unit. */
    private static final double MIN_GRAMS_PER_UNIT = 3.0;
    private static final double MAX_GRAMS_PER_UNIT = 30.0;
```

Add these imports:

```java
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
```

Delete the entire `resolveIsf(String userIdStr)` method and replace it, plus `calculateRecommendedInsulin`, with:

```java
    /**
     * Loads the user's settings, refusing when they cannot support a dose calculation.
     * Unlike the display paths, dosing never substitutes a population default: an
     * unconfigured parameter is a reason to stop, not a reason to guess.
     */
    private UserSettingsDTO requireSettings(String userIdStr) {
        if (userIdStr == null || userIdStr.isBlank()) {
            throw new DosingRefusedException(DosingRefusalReason.SETTINGS_INVALID, "userId absent");
        }
        UUID userId;
        try {
            userId = UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            throw new DosingRefusedException(DosingRefusalReason.SETTINGS_INVALID,
                    "userId not a UUID: " + userIdStr);
        }
        UserSettingsDTO settings = userSettingsService.getUserSettings(userId);
        if (settings == null) {
            throw new DosingRefusedException(DosingRefusalReason.SETTINGS_INVALID,
                    "no settings row for " + userId);
        }
        return settings;
    }

    /**
     * Derives the insulin:carb ratio in grams per unit.
     *
     * <p>{@code carbRatio} is mmol/L of rise per 10 g; {@code isf} is mmol/L per unit. So
     * {@code gramsPerUnit = 10 * isf / carbRatio}. Deriving it means the ratio inherits the
     * digital twin's per-user calibration of both parameters for free.
     *
     * <p>The envelope is a refusal boundary, not a clamp: a derived 1 g/U means ISF and
     * carbRatio are mutually inconsistent, and silently substituting 3.0 would hide that
     * while still producing a wrong dose.
     */
    private double resolveGramsPerUnit(UserSettingsDTO settings) {
        Double isf = settings.getIsf();
        Double carbRatio = settings.getCarbRatio();
        if (isf == null || carbRatio == null
                || !Double.isFinite(isf) || !Double.isFinite(carbRatio)
                || isf <= 0 || carbRatio <= 0) {
            throw new DosingRefusedException(DosingRefusalReason.SETTINGS_INVALID,
                    "isf=" + isf + " carbRatio=" + carbRatio);
        }
        double gramsPerUnit = 10.0 * isf / carbRatio;
        if (gramsPerUnit < MIN_GRAMS_PER_UNIT || gramsPerUnit > MAX_GRAMS_PER_UNIT) {
            throw new DosingRefusedException(DosingRefusalReason.INSULIN_PARAMS_INCONSISTENT,
                    "derived gramsPerUnit=" + gramsPerUnit + " from isf=" + isf + " carbRatio=" + carbRatio);
        }
        return gramsPerUnit;
    }

    public InsulinCalculationResponse calculateRecommendedInsulin(InsulinCalculationRequest request) {
        UserSettingsDTO settings = requireSettings(request.getUserId());
        double gramsPerUnit = resolveGramsPerUnit(settings);
        double isf = settings.getIsf();

        if (request.getCarbs() == null || request.getCurrentGlucose() == null
                || request.getTargetGlucose() == null) {
            throw new DosingRefusedException(DosingRefusalReason.INVALID_INPUT,
                    "carbs/currentGlucose/targetGlucose must all be present");
        }
        double carbs = request.getCarbs();
        double currentGlucose = request.getCurrentGlucose();
        double targetGlucose = request.getTargetGlucose();

        double mealDose = carbs / gramsPerUnit;
        double correctionDose = (currentGlucose - targetGlucose) / isf;
        double activeInsulin = request.getActiveInsulin() != null ? request.getActiveInsulin() : 0.0;
        double recommendedInsulin = Math.max(0.0, mealDose + correctionDose - activeInsulin);

        return InsulinCalculationResponse.builder()
                .recommendedInsulin(Math.round(recommendedInsulin * 100.0) / 100.0)
                .calculationTime(LocalDateTime.now())
                .build();
    }
```

> Note that `correctionDose` is now **signed**. That single change is the negative-correction fix from spec §3.3: when `currentGlucose < targetGlucose` the meal dose is reduced instead of the below-target case being ignored. Task 3 adds the hypoglycemia floor beneath it.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests '*InsulinCalculatorServiceTest'
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java \
        src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceTest.java
git commit -m "fix: derive insulin:carb ratio from ISF and carb ratio (C1)

The bolus calculator hardcoded 12 g/U and ignored user settings entirely,
recommending 2-2.5x the correct meal dose for an insulin-sensitive patient.
Root cause: user_settings.carb_ratio is a glucose-rise coefficient, not an
insulin:carb ratio, so there was no g/U value to read.

Derives it as 10 * ISF / carbRatio, refusing outside 3-30 g/U rather than
clamping. Also removes the now-unused DEFAULT_ISF fallback."
```

---

### Task 3: C2 — hypoglycemia refusal

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java` (`calculateRecommendedInsulin`)
- Test: `src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceTest.java`

**Interfaces:**
- Consumes: `DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD` (Task 1); `calculateRecommendedInsulin` as left by Task 2.
- Produces: constant `HYPO_REFUSAL_THRESHOLD_MMOL = 3.9`. No signature change.

- [ ] **Step 1: Write the failing tests**

Append to `InsulinCalculatorServiceTest`:

```java
    // -- C2: hypoglycemia handling ---------------------------------------------

    @Test
    void glucoseBelowHypoThreshold_refuses_noDoseReturned() {
        givenSettings(2.0, 2.2, 70.0);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(60.0, 3.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(ex -> ((DosingRefusedException) ex).getReason())
                .isEqualTo(DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD);
    }

    @Test
    void glucoseExactlyAtThreshold_isAllowed() {
        givenSettings(2.0, 2.2, 70.0);
        // 3.9 is the refusal floor, not itself refused.
        assertThat(service.calculateRecommendedInsulin(doseRequest(0.0, 3.9, 3.9))
                .getRecommendedInsulin()).isEqualTo(0.0);
    }

    @Test
    void belowTargetButAboveThreshold_reducesTheMealDose() {
        givenSettings(2.0, 2.2, 70.0);
        // 11 g/U -> 55 g = 5.0 U meal. Correction (5.0 - 6.0) / 2.2 = -0.4545 U.
        // 5.0 - 0.4545 = 4.55 U after rounding.
        InsulinCalculationResponse response =
                service.calculateRecommendedInsulin(doseRequest(55.0, 5.0, 6.0));

        assertThat(response.getRecommendedInsulin()).isEqualTo(4.55);
    }

    @Test
    void largeNegativeCorrection_floorsAtZero_neverNegative() {
        givenSettings(2.0, 2.2, 70.0);
        // Tiny meal, well below target: correction dominates and would go negative.
        InsulinCalculationResponse response =
                service.calculateRecommendedInsulin(doseRequest(5.0, 4.0, 9.0));

        assertThat(response.getRecommendedInsulin()).isEqualTo(0.0);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew test --tests '*InsulinCalculatorServiceTest'
```

Expected: `glucoseBelowHypoThreshold_refuses_noDoseReturned` FAILS — no exception is thrown, a dose is returned. The other three should already pass from Task 2's signed correction; confirm that they do.

- [ ] **Step 3: Add the threshold and the guard**

Add the constant beside `MIN_GRAMS_PER_UNIT`:

```java
    /** ADA/ATTD Level 1 hypoglycemia. Below this the clinical action is to treat, not to bolus. */
    private static final double HYPO_REFUSAL_THRESHOLD_MMOL = 3.9;
```

In `calculateRecommendedInsulin`, immediately after the three locals `carbs` / `currentGlucose` / `targetGlucose` are assigned and before `mealDose` is computed:

```java
        if (currentGlucose < HYPO_REFUSAL_THRESHOLD_MMOL) {
            throw new DosingRefusedException(DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD,
                    "currentGlucose=" + currentGlucose + " < " + HYPO_REFUSAL_THRESHOLD_MMOL);
        }
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests '*InsulinCalculatorServiceTest'
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java \
        src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceTest.java
git commit -m "fix: refuse to bolus below 3.9 mmol/L, reduce dose below target (C2)

A patient at 3.0 mmol/L previously received a full meal dose with no
reduction and no warning, because the correction was applied only when
glucose exceeded target."
```

---

### Task 4: C2 — maximum-bolus ceiling

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java` (`calculateRecommendedInsulin`)
- Test: `src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceTest.java`

**Interfaces:**
- Consumes: `DosingRefusalReason.DOSE_EXCEEDS_MAX_BOLUS` (Task 1).
- Produces: `private double maxBolusUnits(UserSettingsDTO settings)`; constants `MAX_BOLUS_UNITS_PER_KG = 0.3`, `DEFAULT_BODY_WEIGHT_KG = 70.0`.

> **Evaluation order matters:** the ceiling is checked against the *final* dose — after meal, correction, and IOB subtraction — because that is the number a patient would act on.

- [ ] **Step 1: Write the failing tests**

Append to `InsulinCalculatorServiceTest`:

```java
    // -- C2: maximum bolus ceiling ---------------------------------------------

    @Test
    void doseAboveWeightBasedCeiling_refuses_ratherThanClamping() {
        // 60 kg -> ceiling 18.0 U. 11 g/U, 250 g -> 22.7 U meal alone.
        givenSettings(2.0, 2.2, 60.0);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(250.0, 6.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(ex -> ((DosingRefusedException) ex).getReason())
                .isEqualTo(DosingRefusalReason.DOSE_EXCEEDS_MAX_BOLUS);
    }

    @Test
    void nullBodyWeight_usesThe70kgFallbackCeiling() {
        // null weight -> 70 kg -> ceiling 21.0 U. 11 g/U, 240 g -> 21.8 U, just over.
        givenSettings(2.0, 2.2, null);

        assertThatThrownBy(() -> service.calculateRecommendedInsulin(doseRequest(240.0, 6.0, 6.0)))
                .isInstanceOf(DosingRefusedException.class)
                .extracting(ex -> ((DosingRefusedException) ex).getReason())
                .isEqualTo(DosingRefusalReason.DOSE_EXCEEDS_MAX_BOLUS);
    }

    @Test
    void doseUnderCeiling_isReturnedNormally() {
        givenSettings(2.0, 2.2, 60.0);
        // 110 g / 11 = 10.0 U, under the 18.0 U ceiling.
        assertThat(service.calculateRecommendedInsulin(doseRequest(110.0, 6.0, 6.0))
                .getRecommendedInsulin()).isEqualTo(10.0);
    }

    @Test
    void ceilingIsCheckedAfterIobSubtraction_notBefore() {
        givenSettings(2.0, 2.2, 60.0);   // ceiling 18.0 U
        // 220 g / 11 = 20.0 U gross, minus 5 U IOB = 15.0 U net -> allowed.
        InsulinCalculationRequest request = InsulinCalculationRequest.builder()
                .carbs(220.0).currentGlucose(6.0).targetGlucose(6.0)
                .activeInsulin(5.0).userId(USER_ID.toString()).build();

        assertThat(service.calculateRecommendedInsulin(request).getRecommendedInsulin())
                .isEqualTo(15.0);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew test --tests '*InsulinCalculatorServiceTest'
```

Expected: the two refusal tests FAIL (a large dose is returned instead of an exception).

- [ ] **Step 3: Add the ceiling**

Add the constants beside `HYPO_REFUSAL_THRESHOLD_MMOL`:

```java
    /** Maximum single bolus, units per kg of body weight. 0.3 U/kg is large for any patient. */
    private static final double MAX_BOLUS_UNITS_PER_KG = 0.3;
    /** Population fallback when body weight is unset (matches the schema comment on body_weight_kg). */
    private static final double DEFAULT_BODY_WEIGHT_KG = 70.0;
```

Add the helper next to `resolveGramsPerUnit`:

```java
    /** Weight-scaled ceiling, so the bound is sane for a child and for an adult alike. */
    private double maxBolusUnits(UserSettingsDTO settings) {
        Double weight = settings.getBodyWeightKg();
        double effectiveWeight = (weight != null && Double.isFinite(weight) && weight > 0)
                ? weight : DEFAULT_BODY_WEIGHT_KG;
        return MAX_BOLUS_UNITS_PER_KG * effectiveWeight;
    }
```

In `calculateRecommendedInsulin`, replace the `return` block with:

```java
        double maxBolus = maxBolusUnits(settings);
        if (recommendedInsulin > maxBolus) {
            throw new DosingRefusedException(DosingRefusalReason.DOSE_EXCEEDS_MAX_BOLUS,
                    "recommended=" + recommendedInsulin + " exceeds max=" + maxBolus);
        }

        return InsulinCalculationResponse.builder()
                .recommendedInsulin(Math.round(recommendedInsulin * 100.0) / 100.0)
                .calculationTime(LocalDateTime.now())
                .build();
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests '*InsulinCalculatorServiceTest'
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java \
        src/test/java/che/glucosemonitorbe/service/InsulinCalculatorServiceTest.java
git commit -m "fix: refuse doses above 0.3 U/kg (C2)

Bounds the catastrophic output regardless of whether ISF and carb ratio
were ever configured - which, with the schema defaults left in place, the
application cannot determine."
```

---

### Task 5: C2 — request validation and the 422 wire contract

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/dto/InsulinCalculationRequest.java`
- Modify: `src/main/java/che/glucosemonitorbe/controller/InsulinCalculatorController.java`
- Test: `src/test/java/che/glucosemonitorbe/controller/InsulinCalculatorControllerTest.java`

**Interfaces:**
- Consumes: `DosingRefusalReason.{INVALID_INPUT, GLUCOSE_IMPLAUSIBLE_UNIT}` (Task 1); the refusing service from Tasks 2–4.
- Produces: the wire contract — 422 with the reason code in `error`.

> **Two things that will bite you.** First, `@Valid` is enforced by Spring MVC, not by Java: the existing `InsulinCalculatorControllerTest` calls `controller.calculateInsulin(request, auth)` **directly**, so validation never fires there. New validation tests must go through `MockMvcBuilders.standaloneSetup`. Second, `GlobalExceptionHandler` maps `MethodArgumentNotValidException` to **400** for the whole application; a controller-local `@ExceptionHandler` takes precedence and is how this one endpoint returns 422 without changing every other endpoint.

- [ ] **Step 1: Write the failing tests**

Add these imports to `src/test/java/che/glucosemonitorbe/controller/InsulinCalculatorControllerTest.java`:

```java
import che.glucosemonitorbe.exception.DosingRefusalReason;
import che.glucosemonitorbe.exception.DosingRefusedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
```

Then add to the class:

```java
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Validation only fires through the MVC stack. Calling the controller method directly
     * (as the BE-5 tests above do) bypasses @Valid entirely.
     */
    private MockMvc mvc() {
        if (mockMvc == null) {
            mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        }
        return mockMvc;
    }

    private void backendEnabled() {
        when(featureToggleService.shouldUseBackend("insulin-calculator")).thenReturn(true);
        when(featureToggleService.shouldMigrate(eq("insulin-calculator"), any())).thenReturn(true);
    }

    @Test
    @DisplayName("C2: a mg/dL payload is rejected as an implausible unit, not dosed")
    void mgPerDlPayload_returns422_withUnitReason() throws Exception {
        backendEnabled();
        InsulinCalculationRequest request = InsulinCalculationRequest.builder()
                .carbs(60.0).currentGlucose(180.0).targetGlucose(100.0).activeInsulin(0.0).build();

        mvc().perform(post("/api/insulin/calculate")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("GLUCOSE_IMPLAUSIBLE_UNIT"));
    }

    @Test
    @DisplayName("C2: targetGlucose of 0 is rejected")
    void zeroTargetGlucose_returns422() throws Exception {
        backendEnabled();
        InsulinCalculationRequest request = InsulinCalculationRequest.builder()
                .carbs(60.0).currentGlucose(8.0).targetGlucose(0.0).activeInsulin(0.0).build();

        mvc().perform(post("/api/insulin/calculate")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("C2: missing carbs is rejected")
    void missingCarbs_returns422() throws Exception {
        backendEnabled();
        InsulinCalculationRequest request = InsulinCalculationRequest.builder()
                .currentGlucose(8.0).targetGlucose(5.5).activeInsulin(0.0).build();

        mvc().perform(post("/api/insulin/calculate")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("C2: a service refusal is not swallowed into a generic 400")
    void serviceRefusal_propagates() {
        backendEnabled();
        when(insulinCalculatorService.calculateRecommendedInsulin(any()))
                .thenThrow(new DosingRefusedException(
                        DosingRefusalReason.GLUCOSE_BELOW_SAFE_THRESHOLD, "currentGlucose=3.0"));

        InsulinCalculationRequest request = InsulinCalculationRequest.builder()
                .carbs(60.0).currentGlucose(4.5).targetGlucose(5.5).activeInsulin(0.0).build();

        assertThatThrownBy(() -> controller.calculateInsulin(request, auth))
                .isInstanceOf(DosingRefusedException.class);
    }
```

Add `import static org.assertj.core.api.Assertions.assertThatThrownBy;` and `import static org.mockito.ArgumentMatchers.eq;` if not already present.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew test --tests '*InsulinCalculatorControllerTest'
```

Expected: the three MockMvc tests FAIL with 200 instead of 422 (no constraints yet). `serviceRefusal_propagates` FAILS because the blanket `catch (Exception e)` converts the refusal into a 400 body.

- [ ] **Step 3: Add the constraints to the DTO**

Replace the field block in `src/main/java/che/glucosemonitorbe/dto/InsulinCalculationRequest.java`, adding the imports:

```java
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
```

```java
    @NotNull
    @DecimalMin("0.0")
    @DecimalMax("300.0")
    private Double carbs;

    /**
     * mmol/L. The 33.0 ceiling is the mg/dL guard: any plausible mg/dL reading (70-400)
     * exceeds the physiological mmol/L range, so the unit mistake is rejected rather than
     * guessed at.
     */
    @NotNull
    @DecimalMin("1.0")
    @DecimalMax("33.0")
    private Double currentGlucose;

    @NotNull
    @DecimalMin("4.0")
    @DecimalMax("10.0")
    private Double targetGlucose;

    @DecimalMin("0.0")
    @DecimalMax("50.0")
    private Double activeInsulin;

    private String mealType;
    private String userId;
```

Leave the `clientTimeInfo` field and its Javadoc untouched.

- [ ] **Step 4: Wire validation and refusals through the controller**

In `src/main/java/che/glucosemonitorbe/controller/InsulinCalculatorController.java`, add the imports:

```java
import che.glucosemonitorbe.exception.CustomErrorResponse;
import che.glucosemonitorbe.exception.DosingRefusalReason;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
```

Change the method signature to validate the body:

```java
    public ResponseEntity<?> calculateInsulin(@Valid @RequestBody InsulinCalculationRequest request,
                                              Authentication authentication) {
```

Replace the entire `try { ... } catch (Exception e) { ... }` block at the end of `calculateInsulin` with an unguarded call — `GlobalExceptionHandler` now owns both the 422 and the 500 paths, and it logs:

```java
        InsulinCalculationResponse response = insulinCalculatorService.calculateRecommendedInsulin(request);
        return ResponseEntity.ok(Map.of(
            "data", response,
            "featureEnabled", true,
            "backendMode", true,
            "message", "Calculation completed using backend service"
        ));
```

Add this handler as the last method in the class. It is controller-local on purpose: `GlobalExceptionHandler` maps validation failures to 400 for every other endpoint, and that stays true.

```java
    /**
     * Validation failures on a dose request are 422, not 400: the body parsed fine, but the
     * values are not safe to dose from. A currentGlucose DecimalMax violation specifically
     * means a probable mg/dL payload, which is worth telling the client apart.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CustomErrorResponse> handleInvalidDoseRequest(
            MethodArgumentNotValidException ex, HttpServletRequest httpRequest) {
        boolean impossibleGlucose = ex.getBindingResult().getFieldErrors().stream()
                .anyMatch(e -> "currentGlucose".equals(e.getField()) && "DecimalMax".equals(e.getCode()));
        DosingRefusalReason reason = impossibleGlucose
                ? DosingRefusalReason.GLUCOSE_IMPLAUSIBLE_UNIT
                : DosingRefusalReason.INVALID_INPUT;
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new CustomErrorResponse(
                        HttpStatus.UNPROCESSABLE_ENTITY.value(),
                        reason.name(),
                        reason.getMessage(),
                        httpRequest.getRequestURI()));
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

```bash
./gradlew test --tests '*InsulinCalculatorControllerTest'
```

Expected: all PASS, including the two pre-existing BE-5 tests.

- [ ] **Step 6: Reconcile the integration test**

`InsulinCalculatorIntegrationTest` registers a fresh user, so `user_settings` carries the schema defaults `carbRatio=2.0, isf=1.0` → derived **5.0 g/U**, and `body_weight_kg` is null → ceiling **21.0 U**. Recheck each case:

- `calculateBolus_withValidInputs_returnsRecommendedInsulin` — carbs 60, glucose 8.5→5.5: `60/5.0 + 3.0/1.0 = 15.0 U`, under the ceiling. Previously 8.0 U. If the test asserts a *specific* number, update it to 15.0; if it only asserts 2xx, leave it.
- `calculate_withForeignUserId_stillSucceeds` — carbs 48, glucose 8.0→5.5: `9.6 + 2.5 = 12.1 U`, under the ceiling. Still 2xx.
- The `carbs 0.0, glucose 5.5, target 5.5` case — yields 0.0 U and asserts only "not 500". Still passes.

Run it (Docker must be running for Testcontainers):

```bash
./gradlew test --tests '*InsulinCalculatorIntegrationTest'
```

Expected: PASS. Fix any hardcoded dose assertion to the values above; do not weaken an assertion to make it pass.

- [ ] **Step 7: Run the full suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/dto/InsulinCalculationRequest.java \
        src/main/java/che/glucosemonitorbe/controller/InsulinCalculatorController.java \
        src/test/java/che/glucosemonitorbe/controller/InsulinCalculatorControllerTest.java \
        src/test/java/che/glucosemonitorbe/integration/InsulinCalculatorIntegrationTest.java
git commit -m "fix: validate dose requests, return 422 with a reason code (C2)

The request DTO had no constraints and the controller no @Valid, so a
mg/dL payload (180 -> 100) produced a 36 U correction. The blanket
catch(Exception) also converted every refusal into an unlogged generic
400, leaving no trace of a misbehaving dose calculation."
```

---

### Task 6: C3 — fail closed when notes cannot be loaded

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java:496-523`
- Test: `src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsServiceTest.java`

**Interfaces:**
- Consumes: the `DataAccessException` → 503 mapping from Task 1.
- Produces: no new API. `getRecentNotes` and `getLongActingNotes` keep their signatures and now propagate.

> `GlucoseCalculationsController` already rethrows `RuntimeException` so the global handler can map it (its "BUG A1 fix" comment says so). No controller change is needed — deleting the swallow in the service is the whole fix.

- [ ] **Step 1: Write the failing test**

Append to `src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsServiceTest.java`. Add these imports:

```java
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

```java
    @Test
    @DisplayName("C3: a note-load failure must not be reported as zero insulin on board")
    void noteLoadFailure_propagates_ratherThanReportingZeroIob() {
        UUID userId = UUID.randomUUID();
        when(userService.getUserByUsername("alice"))
                .thenReturn(UserDto.builder().id(userId).username("alice").build());
        when(noteRepository.findByUserIdAndTimestampBetween(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        GlucoseCalculationsRequest request = new GlucoseCalculationsRequest();
        request.setUserId("alice");
        request.setCurrentGlucose(8.0);

        assertThatThrownBy(() -> service.calculateGlucoseData(request))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
```

> Match the existing file's setup conventions — reuse whatever mock fields and `@BeforeEach` wiring it already declares for `userService`, `noteRepository`, and `service` rather than re-declaring them. If `GlucoseCalculationsRequest` is built via a Lombok builder in that file, use the builder instead of setters.

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests '*GlucoseCalculationsServiceTest'
```

Expected: FAIL — no exception is thrown, because the service catches it and returns an empty list, producing a response with `activeInsulinOnBoard = 0.0`.

- [ ] **Step 3: Remove both swallow blocks**

In `src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java`, replace `getLongActingNotes` with:

```java
    /**
     * Load long-acting (basal) insulin notes from the last 36 hours.
     * Lantus/Tresiba have a DIA of ~24-28 h, so a dose taken yesterday must be included
     * for accurate EGP suppression modelling in the Hovorka path.
     *
     * <p>Repository failures propagate deliberately: returning an empty list here would
     * report zero basal on board as fact.
     */
    private List<Note> getLongActingNotes(UUID userId, LocalDateTime currentTime) {
        LocalDateTime since = currentTime.minusHours(36);
        return noteRepository.findByUserIdAndTimestampBetween(userId, since, currentTime)
                .stream()
                .filter(Note::isLongActing)
                .toList();
    }
```

And `getRecentNotes` with:

```java
    /**
     * Get recent notes for a user from the last 8 hours.
     * BUG P1 fix: accepts UUID directly so getUserByUsername is only called once per request.
     * BUG L2 fix: window extended from 6 h to 8 h so high-fat/high-protein (HFHP) meals
     * whose absorption peaks at up to 8 h are included in the COB calculation.
     *
     * <p>C3: repository failures propagate. A swallowed exception here produced
     * {@code activeInsulinOnBoard = 0.00} as an HTTP 200, which the iOS client caches - a
     * patient who bolused 30 minutes ago would see "no active insulin" and could stack a
     * correction dose. An error is the only representation that cannot be misread as data.
     */
    private List<Note> getRecentNotes(UUID userId, LocalDateTime currentTime) {
        LocalDateTime startTime = currentTime.minusHours(8);
        return noteRepository.findByUserIdAndTimestampBetween(userId, startTime, currentTime);
    }
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
./gradlew test --tests '*GlucoseCalculationsServiceTest'
```

Expected: PASS. If other tests in this class relied on the swallow to simulate "no notes", change them to return `List.of()` rather than throwing.

- [ ] **Step 5: Run the full suite**

```bash
./gradlew test
```

Expected: BUILD SUCCESSFUL. `ExperimentService.checkBackground` shares `activeCobIobInputs`, so watch for failures there.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java \
        src/test/java/che/glucosemonitorbe/service/GlucoseCalculationsServiceTest.java
git commit -m "fix: fail closed when notes cannot be loaded (C3)

A transient DB fault produced activeCarbsOnBoard=0.0 and
activeInsulinOnBoard=0.0 as HTTP 200 with no error signal, cached by the
iOS client. Now propagates to a 503."
```

---

### Task 7: H1 — keep hypo-contaminated meals out of titration

**Files:**
- Modify: `src/main/java/che/glucosemonitorbe/service/VerificationService.java:270-280`
- Test: `src/test/java/che/glucosemonitorbe/service/VerificationServiceTest.java` (new file)

**Interfaces:**
- Consumes: `CgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(UUID, Long, Long)` and `NoteRepository.findByUserIdAndTimestampBetween(UUID, LocalDateTime, LocalDateTime)` — both already exist, no new repository methods.
- Produces: skip reasons `"hypo_in_window"` and `"rescue_carbs_in_window"` on `VerificationEvent`.

> **The failure this closes:** `actualDelta = twoHour - baseline` is taken at face value. A meal followed by a low followed by rescue carbs shows a *rise*, so `meanError > 0`, so carb ratio is suggested **upward**, so predicted rise grows, so doses grow, so more hypos. Excluding these meals severs the loop.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/che/glucosemonitorbe/service/VerificationServiceTest.java`:

```java
package che.glucosemonitorbe.service;

import che.glucosemonitorbe.domain.CgmReading;
import che.glucosemonitorbe.entity.Note;
import che.glucosemonitorbe.entity.VerificationEvent;
import che.glucosemonitorbe.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VerificationServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID NOTE_ID = UUID.randomUUID();
    private static final LocalDateTime MEAL_TIME = LocalDateTime.of(2026, 3, 1, 12, 0);

    private VerificationEventRepository verificationEventRepository;
    private VerificationSummaryRepository verificationSummaryRepository;
    private NoteRepository noteRepository;
    private UserSettingsRepository userSettingsRepository;
    private CgmReadingRepository cgmReadingRepository;
    private VerificationService service;

    @BeforeEach
    void setUp() {
        verificationEventRepository = mock(VerificationEventRepository.class);
        verificationSummaryRepository = mock(VerificationSummaryRepository.class);
        noteRepository = mock(NoteRepository.class);
        userSettingsRepository = mock(UserSettingsRepository.class);
        cgmReadingRepository = mock(CgmReadingRepository.class);
        service = new VerificationService(
                verificationEventRepository, verificationSummaryRepository,
                noteRepository, userSettingsRepository, cgmReadingRepository);
    }

    private Note qualifyingMeal() {
        Note note = new Note();
        note.setId(NOTE_ID);
        note.setUserId(USER_ID);
        note.setTimestamp(MEAL_TIME);
        note.setCarbs(50.0);
        note.setInsulin(5.0);
        return note;
    }

    /** sgv is mg/dL in storage; 3.5 mmol/L is ~63 mg/dL, 8.0 mmol/L is ~144 mg/dL. */
    private CgmReading readingAt(LocalDateTime time, int sgvMgDl) {
        CgmReading reading = new CgmReading();
        reading.setUserId(USER_ID);
        reading.setDateTimestamp(time.toInstant(ZoneOffset.UTC).toEpochMilli());
        reading.setSgv(sgvMgDl);
        return reading;
    }

    private VerificationEvent pendingEvent() {
        return VerificationEvent.builder()
                .id(UUID.randomUUID())
                .userId(USER_ID)
                .noteId(NOTE_ID)
                .status(VerificationEvent.Status.PENDING)
                .build();
    }

    private VerificationEvent evaluateAndCapture() {
        when(verificationEventRepository.findPendingReadyToEvaluate(any()))
                .thenReturn(List.of(pendingEvent()));
        when(noteRepository.findById(NOTE_ID)).thenReturn(Optional.of(qualifyingMeal()));
        service.evaluatePending();

        ArgumentCaptor<VerificationEvent> captor = ArgumentCaptor.forClass(VerificationEvent.class);
        verify(verificationEventRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("H1: a low inside the 2 h window disqualifies the meal from titration")
    void hypoInWindow_isSkipped() {
        // Stacking check looks backwards; return no prior insulin notes.
        when(noteRepository.findByUserIdAndTimestampBetween(
                eq(USER_ID), eq(MEAL_TIME.minusHours(3)), eq(MEAL_TIME)))
                .thenReturn(List.of());
        // Forward window: no rescue notes.
        when(noteRepository.findByUserIdAndTimestampBetween(
                eq(USER_ID), eq(MEAL_TIME), eq(MEAL_TIME.plusHours(2))))
                .thenReturn(List.of());
        // 63 mg/dL = 3.5 mmol/L, below the 3.9 threshold.
        when(cgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                eq(USER_ID), any(), any()))
                .thenReturn(List.of(readingAt(MEAL_TIME.plusMinutes(90), 63)));

        VerificationEvent saved = evaluateAndCapture();

        assertThat(saved.getStatus()).isEqualTo(VerificationEvent.Status.SKIPPED);
        assertThat(saved.getSkipReason()).isEqualTo("hypo_in_window");
    }

    @Test
    @DisplayName("H1: a carbs-only note in the window reads as a rescue and disqualifies the meal")
    void rescueCarbsInWindow_isSkipped() {
        Note rescue = new Note();
        rescue.setId(UUID.randomUUID());
        rescue.setUserId(USER_ID);
        rescue.setTimestamp(MEAL_TIME.plusMinutes(75));
        rescue.setCarbs(15.0);
        rescue.setInsulin(null);

        when(noteRepository.findByUserIdAndTimestampBetween(
                eq(USER_ID), eq(MEAL_TIME.minusHours(3)), eq(MEAL_TIME)))
                .thenReturn(List.of());
        when(noteRepository.findByUserIdAndTimestampBetween(
                eq(USER_ID), eq(MEAL_TIME), eq(MEAL_TIME.plusHours(2))))
                .thenReturn(List.of(rescue));
        // All readings in range - no hypo, so the rescue check is what must fire.
        when(cgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                eq(USER_ID), any(), any()))
                .thenReturn(List.of(readingAt(MEAL_TIME.plusMinutes(90), 144)));

        VerificationEvent saved = evaluateAndCapture();

        assertThat(saved.getStatus()).isEqualTo(VerificationEvent.Status.SKIPPED);
        assertThat(saved.getSkipReason()).isEqualTo("rescue_carbs_in_window");
    }

    @Test
    @DisplayName("H1: a clean meal is still evaluated")
    void cleanMeal_isNotSkipped() {
        when(noteRepository.findByUserIdAndTimestampBetween(
                eq(USER_ID), eq(MEAL_TIME.minusHours(3)), eq(MEAL_TIME)))
                .thenReturn(List.of());
        when(noteRepository.findByUserIdAndTimestampBetween(
                eq(USER_ID), eq(MEAL_TIME), eq(MEAL_TIME.plusHours(2))))
                .thenReturn(List.of());
        when(cgmReadingRepository.findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                eq(USER_ID), any(), any()))
                .thenReturn(List.of(readingAt(MEAL_TIME.plusMinutes(90), 144)));
        when(userSettingsRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(verificationEventRepository.findCompletedByUserId(USER_ID)).thenReturn(List.of());
        when(verificationSummaryRepository.findById(USER_ID)).thenReturn(Optional.empty());

        VerificationEvent saved = evaluateAndCapture();

        assertThat(saved.getSkipReason()).isNotIn("hypo_in_window", "rescue_carbs_in_window");
    }
}
```

> If `VerificationService`'s constructor parameter order differs from the one above, match the field declaration order in the class — it is `@RequiredArgsConstructor`, so the order is exactly the field order.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
./gradlew test --tests '*VerificationServiceTest'
```

Expected: the first two FAIL — the meals are evaluated rather than skipped, because neither check exists yet.

- [ ] **Step 3: Add the two eligibility checks**

In `src/main/java/che/glucosemonitorbe/service/VerificationService.java`, add the constant next to `MIN_CARBS`:

```java
    /** ADA/ATTD Level 1 hypoglycemia. A meal whose window contains a low cannot titrate carb ratio. */
    private static final double HYPO_THRESHOLD_MMOL = 3.9;
    /** Post-meal observation window, matching the +2 h evaluation point. */
    private static final int EVALUATION_WINDOW_HOURS = 2;
```

Replace `fullEligibilityCheck` with:

```java
    private String fullEligibilityCheck(Note note, UUID userId) {
        String pre = preCheckEligibility(note);
        if (pre != null) return pre;
        // Stacking check: any other insulin notes in the 3 hours prior?
        LocalDateTime windowStart = note.getTimestamp().minusHours(3);
        List<Note> prior = noteRepository.findByUserIdAndTimestampBetween(userId, windowStart, note.getTimestamp());
        boolean stacked = prior.stream().anyMatch(n -> !n.getId().equals(note.getId())
                && n.getInsulin() != null && n.getInsulin() > 0);
        if (stacked) return "insulin_stacking";

        // H1: a low anywhere in the observation window makes actualDelta uninterpretable.
        // Rescue carbs turn the recovery into an apparent post-meal rise, which would push
        // the carb ratio UP - directly increasing future doses and causing more lows.
        LocalDateTime windowEnd = note.getTimestamp().plusHours(EVALUATION_WINDOW_HOURS);
        List<CgmReading> windowReadings = cgmReadingRepository
                .findByUserIdAndDateTimestampBetweenOrderByDateTimestampAsc(
                        userId, toEpochMs(note.getTimestamp()), toEpochMs(windowEnd));
        boolean hypo = windowReadings.stream()
                .filter(r -> r.getSgv() != null)
                .anyMatch(r -> (r.getSgv() / 18.0) < HYPO_THRESHOLD_MMOL);
        if (hypo) return "hypo_in_window";

        // A carbs-only note in the window is a rescue treatment, not part of the meal.
        List<Note> inWindow = noteRepository.findByUserIdAndTimestampBetween(
                userId, note.getTimestamp(), windowEnd);
        boolean rescueCarbs = inWindow.stream()
                .filter(n -> !n.getId().equals(note.getId()))
                .anyMatch(n -> n.getCarbs() != null && n.getCarbs() > 0
                        && (n.getInsulin() == null || n.getInsulin() <= 0));
        if (rescueCarbs) return "rescue_carbs_in_window";

        return null;
    }
```

`CgmReading` is already imported at the top of the file.

- [ ] **Step 4: Run the tests to verify they pass**

```bash
./gradlew test --tests '*VerificationServiceTest'
```

Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/che/glucosemonitorbe/service/VerificationService.java \
        src/test/java/che/glucosemonitorbe/service/VerificationServiceTest.java
git commit -m "fix: exclude hypo-contaminated meals from carb ratio titration (H1)

actualDelta was taken at face value, so a meal followed by a low followed
by rescue carbs read as a post-meal rise, suggesting carb ratio upward and
driving doses up - a feedback loop toward more hypoglycemia."
```

---

### Task 8: Full verification

**Files:** none modified — this task only runs and reports.

**Interfaces:**
- Consumes: every change from Tasks 1–7.
- Produces: a green suite and a written record of what was verified.

- [ ] **Step 1: Run the whole suite from clean**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
./gradlew clean test
```

Expected: BUILD SUCCESSFUL. Docker must be running for the Testcontainers integration tests.

- [ ] **Step 2: Confirm the four defects are actually closed**

Run each targeted class and confirm the named tests pass:

```bash
./gradlew test --tests '*InsulinCalculatorServiceTest' \
               --tests '*InsulinCalculatorControllerTest' \
               --tests '*GlucoseCalculationsServiceTest' \
               --tests '*VerificationServiceTest' \
               --tests '*GlobalExceptionHandlerDosingTest'
```

Checklist — each maps to a spec defect:
- C1: `mealDose_usesRatioDerivedFromIsfAndCarbRatio_notTheOldHardcoded12`, `derivedRatioBelowEnvelope_refuses_ratherThanClamping`
- C2: `mgPerDlPayload_returns422_withUnitReason`, `zeroTargetGlucose_returns422`, `glucoseBelowHypoThreshold_refuses_noDoseReturned`, `doseAboveWeightBasedCeiling_refuses_ratherThanClamping`
- C3: `noteLoadFailure_propagates_ratherThanReportingZeroIob`
- H1: `hypoInWindow_isSkipped`, `rescueCarbsInWindow_isSkipped`

- [ ] **Step 3: Confirm no fabricated-default paths remain in the touched code**

```bash
grep -n "carbs() / 12\|DEFAULT_ISF" src/main/java/che/glucosemonitorbe/service/InsulinCalculatorService.java
grep -n "return new ArrayList<>()" src/main/java/che/glucosemonitorbe/service/GlucoseCalculationsService.java
```

Expected: no output from either. Any hit means a swallow or a hardcoded ratio survived.

- [ ] **Step 4: Commit**

```bash
git commit --allow-empty -m "chore: verify C1-C3 and H1 remediation

Full suite green. Spec: docs/superpowers/specs/2026-08-01-dosing-safety-remediation-design.md

Not closed by this work, per spec section 2: the schema keeps carb_ratio and
isf NOT NULL with defaults 2.0/1.0, so an unconfigured user still reads
ISF 1.0 and the application cannot tell configured from unconfigured. The
dose ceiling, the g/U envelope and the hypo refusal bound the consequences;
they do not close the path. Spec section 6 lists the fifteen findings that
remain open."
```

---

## Notes for whoever executes this

- **Task order matters.** Task 1 is the foundation for Tasks 2–5. Tasks 6 and 7 are independent of everything else and of each other — they can be done in any order after Task 1 (Task 6 needs Task 1's 503 mapping).
- **Do not weaken an assertion to make a test pass.** If the integration test's expected dose changed, that is the fix working — update the expectation to the value computed in Task 5 Step 6 and say so in the commit.
- **Do not add fallbacks.** Every `?:` or `!= null ? x : DEFAULT` you are tempted to add on a dosing path is the exact pattern this work removes. Refuse instead.
- **Client work is out of scope.** Both frontends will show a generic error on 422/503 until updated. That is degraded, not unsafe — no dose is displayed.
