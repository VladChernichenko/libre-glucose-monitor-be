package che.glucosemonitorbe.circuitbreaker;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit Breaker implementation for external service calls
 * Implements the Circuit Breaker pattern to prevent cascading failures
 */
@Slf4j
public class CircuitBreaker {
    
    public enum State {
        CLOSED,    // Normal operation - requests pass through
        OPEN,      // Circuit is open - requests are rejected immediately
        HALF_OPEN  // Testing if service is back - limited requests allowed
    }
    
    private final String name;
    private final int failureThreshold;
    private final long timeoutDurationMs;
    private final int halfOpenMaxCalls;
    
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);
    private final AtomicReference<LocalDateTime> lastFailureTime = new AtomicReference<>();
    
    public CircuitBreaker(String name, int failureThreshold, long timeoutDurationMs, int halfOpenMaxCalls) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.timeoutDurationMs = timeoutDurationMs;
        this.halfOpenMaxCalls = halfOpenMaxCalls;
    }
    
    /**
     * Execute a call with circuit breaker protection
     */
    public <T> T execute(Supplier<T> callable) throws CircuitBreakerException {
        if (isCircuitOpen()) {
            log.warn("Circuit breaker {} is OPEN - rejecting call", name);
            throw new CircuitBreakerException("Circuit breaker is OPEN for " + name);
        }
        
        try {
            T result = callable.get();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }
    
    /**
     * Execute a call with circuit breaker protection and fallback
     */
    public <T> T executeWithFallback(Supplier<T> callable, Supplier<T> fallback) {
        try {
            return execute(callable);
        } catch (CircuitBreakerException e) {
            log.warn("Circuit breaker {} is OPEN - using fallback", name);
            return fallback.get();
        } catch (Exception e) {
            log.error("Call failed for circuit breaker {} - using fallback", name, e);
            return fallback.get();
        }
    }
    
    /**
     * Check if circuit is currently open
     */
    public boolean isCircuitOpen() {
        State currentState = state.get();
        
        if (currentState == State.CLOSED) {
            return false;
        }
        
        if (currentState == State.OPEN) {
            // Check if timeout has passed to transition to HALF_OPEN
            LocalDateTime lastFailure = lastFailureTime.get();
            if (lastFailure != null && 
                ChronoUnit.MILLIS.between(lastFailure, LocalDateTime.now()) >= timeoutDurationMs) {
                
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenCalls.set(0);
                    successCount.set(0);
                    log.info("Circuit breaker {} transitioned to HALF_OPEN", name);
                }
            }
            return true;
        }
        
        // HALF_OPEN state
        return halfOpenCalls.get() >= halfOpenMaxCalls;
    }
    
    /**
     * Handle successful call
     */
    private void onSuccess() {
        State currentState = state.get();
        
        if (currentState == State.HALF_OPEN) {
            int calls = halfOpenCalls.incrementAndGet();
            int successes = successCount.incrementAndGet();
            
            log.debug("Circuit breaker {} HALF_OPEN: {}/{} calls successful", name, successes, calls);
            
            // If we have enough successful calls, close the circuit
            if (successes >= halfOpenMaxCalls) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    failureCount.set(0);
                    halfOpenCalls.set(0);
                    successCount.set(0);
                    log.info("Circuit breaker {} transitioned to CLOSED", name);
                }
            }
        } else if (currentState == State.CLOSED) {
            // Reset failure count on success
            failureCount.set(0);
        }
    }
    
    /**
     * Handle failed call
     */
    private void onFailure() {
        int failures = failureCount.incrementAndGet();
        lastFailureTime.set(LocalDateTime.now());
        
        log.debug("Circuit breaker {} failure count: {}/{}", name, failures, failureThreshold);
        
        if (failures >= failureThreshold) {
            // BE-6 fix: also handle HALF_OPEN → OPEN so a failed test-call re-opens the circuit
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                log.warn("Circuit breaker {} CLOSED → OPEN after {} failures", name, failures);
            } else if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                halfOpenCalls.set(0);
                successCount.set(0);
                log.warn("Circuit breaker {} HALF_OPEN → OPEN after test-call failure", name);
            }
        }
    }
    
    /**
     * Get current circuit breaker state
     */
    public State getState() {
        return state.get();
    }
    
    /**
     * Get current failure count
     */
    public int getFailureCount() {
        return failureCount.get();
    }
    
    /**
     * Get current success count
     */
    public int getSuccessCount() {
        return successCount.get();
    }
    
    /**
     * Reset circuit breaker to CLOSED state
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        halfOpenCalls.set(0);
        lastFailureTime.set(null);
        log.info("Circuit breaker {} reset to CLOSED", name);
    }
    
    /**
     * Get circuit breaker statistics
     */
    public CircuitBreakerStats getStats() {
        return CircuitBreakerStats.builder()
                .name(name)
                .state(state.get())
                .failureCount(failureCount.get())
                .successCount(successCount.get())
                .halfOpenCalls(halfOpenCalls.get())
                .lastFailureTime(lastFailureTime.get())
                .build();
    }
}
