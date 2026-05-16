package che.glucosemonitorbe.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the Circuit Breaker pattern implementation.
 * Covers BE-6 regression: HALF_OPEN → OPEN transition on test-call failure.
 */
class CircuitBreakerTest {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long TIMEOUT_MS = 100;          // short timeout for test speed
    private static final int HALF_OPEN_MAX_CALLS = 2;

    private CircuitBreaker cb;

    @BeforeEach
    void setUp() {
        cb = new CircuitBreaker("test-cb", FAILURE_THRESHOLD, TIMEOUT_MS, HALF_OPEN_MAX_CALLS);
    }

    // ── initial state ──────────────────────────────────────────────────────────

    @Test
    void initialState_isClosed() {
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void closedCircuit_allowsCall() {
        String result = cb.execute(() -> "ok");
        assertThat(result).isEqualTo("ok");
    }

    // ── CLOSED → OPEN transition ───────────────────────────────────────────────

    @Test
    void afterThresholdFailures_circuitOpens() {
        triggerFailures(FAILURE_THRESHOLD);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void belowThreshold_circuitRemainsOpen() {
        triggerFailures(FAILURE_THRESHOLD - 1);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void openCircuit_rejectsCallImmediately() {
        triggerFailures(FAILURE_THRESHOLD);
        assertThatThrownBy(() -> cb.execute(() -> "call"))
                .isInstanceOf(CircuitBreakerException.class);
    }

    @Test
    void successResetFailureCount() {
        triggerFailures(FAILURE_THRESHOLD - 1);
        cb.execute(() -> "ok");                    // success resets count
        triggerFailures(FAILURE_THRESHOLD - 1);    // below threshold again
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── OPEN → HALF_OPEN transition ────────────────────────────────────────────

    @Test
    void afterTimeout_circuitTransitionsToHalfOpen() throws InterruptedException {
        triggerFailures(FAILURE_THRESHOLD);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(TIMEOUT_MS + 10);

        // isCircuitOpen() drives the OPEN→HALF_OPEN transition
        boolean open = cb.isCircuitOpen();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(open).isFalse(); // first call into HALF_OPEN is allowed
    }

    // ── HALF_OPEN → CLOSED transition ─────────────────────────────────────────

    @Test
    void halfOpen_enoughSuccesses_closesCircuit() throws InterruptedException {
        triggerFailures(FAILURE_THRESHOLD);
        Thread.sleep(TIMEOUT_MS + 10);

        // Drain the allowed HALF_OPEN calls with successes
        for (int i = 0; i < HALF_OPEN_MAX_CALLS; i++) {
            cb.execute(() -> "ok");
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpen_afterClose_failureCountReset() throws InterruptedException {
        triggerFailures(FAILURE_THRESHOLD);
        Thread.sleep(TIMEOUT_MS + 10);

        for (int i = 0; i < HALF_OPEN_MAX_CALLS; i++) {
            cb.execute(() -> "ok");
        }

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getFailureCount()).isEqualTo(0);
    }

    // ── BE-6 regression: HALF_OPEN → OPEN on test-call failure ───────────────

    /**
     * Before BE-6: a failed test-call in HALF_OPEN only attempted CAS(CLOSED→OPEN)
     * which always fails, so the circuit silently remained HALF_OPEN.
     * After fix: CAS(HALF_OPEN→OPEN) is also tried → circuit re-opens.
     */
    @Test
    void halfOpen_failedTestCall_reOpensCircuit() throws InterruptedException {
        triggerFailures(FAILURE_THRESHOLD);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(TIMEOUT_MS + 10);

        // First call into HALF_OPEN: provoke a failure
        assertThatThrownBy(() -> cb.execute(() -> { throw new RuntimeException("still down"); }))
                .isInstanceOf(RuntimeException.class);

        // BE-6 fix: circuit must be OPEN again, not stuck in HALF_OPEN
        assertThat(cb.getState())
                .as("Circuit breaker should re-open after a failed HALF_OPEN test call (BE-6 fix)")
                .isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void halfOpen_failedTestCall_resetsHalfOpenCounters() throws InterruptedException {
        triggerFailures(FAILURE_THRESHOLD);
        Thread.sleep(TIMEOUT_MS + 10);

        assertThatThrownBy(() -> cb.execute(() -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class);

        assertThat(cb.getSuccessCount()).isEqualTo(0);
    }

    @Test
    void halfOpen_failedTestCall_thenTimeoutAgain_allowsRetry() throws InterruptedException {
        triggerFailures(FAILURE_THRESHOLD);
        Thread.sleep(TIMEOUT_MS + 10);

        // First HALF_OPEN attempt fails → re-opens
        assertThatThrownBy(() -> cb.execute(() -> { throw new RuntimeException("still down"); }))
                .isInstanceOf(RuntimeException.class);

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Wait again; next call should reach HALF_OPEN again
        Thread.sleep(TIMEOUT_MS + 10);
        assertThat(cb.isCircuitOpen()).isFalse();
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
    }

    // ── fallback behaviour ─────────────────────────────────────────────────────

    @Test
    void openCircuit_executeWithFallback_returnsFallbackValue() {
        triggerFailures(FAILURE_THRESHOLD);
        String result = cb.executeWithFallback(() -> "primary", () -> "fallback");
        assertThat(result).isEqualTo("fallback");
    }

    @Test
    void closedCircuit_callThrows_fallbackUsed() {
        String result = cb.executeWithFallback(
                () -> { throw new RuntimeException("oops"); },
                () -> "safe-fallback");
        assertThat(result).isEqualTo("safe-fallback");
    }

    // ── reset ──────────────────────────────────────────────────────────────────

    @Test
    void reset_restoresClosedStateAndZeroCounters() {
        triggerFailures(FAILURE_THRESHOLD);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        cb.reset();

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(cb.getFailureCount()).isEqualTo(0);
        assertThat(cb.getSuccessCount()).isEqualTo(0);
    }

    // ── stats ──────────────────────────────────────────────────────────────────

    @Test
    void getStats_reflectsCurrentState() {
        triggerFailures(2);
        CircuitBreakerStats stats = cb.getStats();
        assertThat(stats.getFailureCount()).isEqualTo(2);
        assertThat(stats.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void triggerFailures(int count) {
        for (int i = 0; i < count; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("simulated failure"); });
            } catch (Exception ignored) {
                // expected
            }
        }
    }
}
