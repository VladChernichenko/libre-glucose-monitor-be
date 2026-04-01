package che.glucosemonitorbe.circuitbreaker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Manages circuit breakers for different services
 */
@Slf4j
@Component
public class CircuitBreakerManager {
    
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    
    // Default configuration
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final long DEFAULT_TIMEOUT_DURATION_MS = 60000; // 1 minute
    private static final int DEFAULT_HALF_OPEN_MAX_CALLS = 3;
    
    /**
     * Get or create a circuit breaker for a service
     */
    public CircuitBreaker getCircuitBreaker(String serviceName) {
        return circuitBreakers.computeIfAbsent(serviceName, name -> {
            log.info("Creating circuit breaker for service: {}", name);
            return new CircuitBreaker(name, DEFAULT_FAILURE_THRESHOLD, DEFAULT_TIMEOUT_DURATION_MS, DEFAULT_HALF_OPEN_MAX_CALLS);
        });
    }
    
    /**
     * Get or create a circuit breaker with custom configuration
     */
    public CircuitBreaker getCircuitBreaker(String serviceName, int failureThreshold, long timeoutDurationMs, int halfOpenMaxCalls) {
        return circuitBreakers.computeIfAbsent(serviceName, name -> {
            log.info("Creating circuit breaker for service: {} with custom config", name);
            return new CircuitBreaker(name, failureThreshold, timeoutDurationMs, halfOpenMaxCalls);
        });
    }
    
    /**
     * Get circuit breaker statistics for all services
     */
    public Map<String, CircuitBreakerStats> getAllStats() {
        Map<String, CircuitBreakerStats> stats = new ConcurrentHashMap<>();
        circuitBreakers.forEach((name, breaker) -> {
            stats.put(name, breaker.getStats());
        });
        return stats;
    }
    
    /**
     * Get circuit breaker statistics for a specific service
     */
    public CircuitBreakerStats getStats(String serviceName) {
        CircuitBreaker breaker = circuitBreakers.get(serviceName);
        return breaker != null ? breaker.getStats() : null;
    }
    
    /**
     * Reset a specific circuit breaker
     */
    public void resetCircuitBreaker(String serviceName) {
        CircuitBreaker breaker = circuitBreakers.get(serviceName);
        if (breaker != null) {
            breaker.reset();
            log.info("Reset circuit breaker for service: {}", serviceName);
        }
    }
    
    /**
     * Reset all circuit breakers
     */
    public void resetAllCircuitBreakers() {
        circuitBreakers.values().forEach(CircuitBreaker::reset);
        log.info("Reset all circuit breakers");
    }
    
    /**
     * Get the number of active circuit breakers
     */
    public int getActiveCircuitBreakerCount() {
        return circuitBreakers.size();
    }
    
    /**
     * Check if any circuit breakers are open
     */
    public boolean hasOpenCircuitBreakers() {
        return circuitBreakers.values().stream()
                .anyMatch(breaker -> breaker.getState() == CircuitBreaker.State.OPEN);
    }
}
