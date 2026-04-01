package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.circuitbreaker.CircuitBreakerManager;
import che.glucosemonitorbe.circuitbreaker.CircuitBreakerStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for circuit breaker monitoring and management
 */
@Slf4j
@RestController
@RequestMapping("/api/circuit-breaker")
@RequiredArgsConstructor
public class CircuitBreakerController {

    private final CircuitBreakerManager circuitBreakerManager;

    /**
     * Get statistics for all circuit breakers
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, CircuitBreakerStats>> getAllStats() {
        try {
            Map<String, CircuitBreakerStats> stats = circuitBreakerManager.getAllStats();
            log.debug("Retrieved circuit breaker stats for {} services", stats.size());
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Failed to retrieve circuit breaker stats", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get statistics for a specific circuit breaker
     */
    @GetMapping("/stats/{serviceName}")
    public ResponseEntity<CircuitBreakerStats> getStats(@PathVariable String serviceName) {
        try {
            CircuitBreakerStats stats = circuitBreakerManager.getStats(serviceName);
            if (stats != null) {
                return ResponseEntity.ok(stats);
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Failed to retrieve circuit breaker stats for service: {}", serviceName, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Reset a specific circuit breaker
     */
    @PostMapping("/reset/{serviceName}")
    public ResponseEntity<String> resetCircuitBreaker(@PathVariable String serviceName) {
        try {
            circuitBreakerManager.resetCircuitBreaker(serviceName);
            log.info("Reset circuit breaker for service: {}", serviceName);
            return ResponseEntity.ok("Circuit breaker reset successfully for service: " + serviceName);
        } catch (Exception e) {
            log.error("Failed to reset circuit breaker for service: {}", serviceName, e);
            return ResponseEntity.internalServerError().body("Failed to reset circuit breaker");
        }
    }

    /**
     * Reset all circuit breakers
     */
    @PostMapping("/reset-all")
    public ResponseEntity<String> resetAllCircuitBreakers() {
        try {
            circuitBreakerManager.resetAllCircuitBreakers();
            log.info("Reset all circuit breakers");
            return ResponseEntity.ok("All circuit breakers reset successfully");
        } catch (Exception e) {
            log.error("Failed to reset all circuit breakers", e);
            return ResponseEntity.internalServerError().body("Failed to reset all circuit breakers");
        }
    }

    /**
     * Get health status of circuit breakers
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> getHealthStatus() {
        try {
            Map<String, CircuitBreakerStats> stats = circuitBreakerManager.getAllStats();
            int totalBreakers = stats.size();
            int openBreakers = (int) stats.values().stream()
                    .filter(stat -> stat.getState() == che.glucosemonitorbe.circuitbreaker.CircuitBreaker.State.OPEN)
                    .count();
            int halfOpenBreakers = (int) stats.values().stream()
                    .filter(stat -> stat.getState() == che.glucosemonitorbe.circuitbreaker.CircuitBreaker.State.HALF_OPEN)
                    .count();
            int closedBreakers = (int) stats.values().stream()
                    .filter(stat -> stat.getState() == che.glucosemonitorbe.circuitbreaker.CircuitBreaker.State.CLOSED)
                    .count();

            Map<String, Object> health = Map.of(
                    "status", openBreakers > 0 ? "degraded" : "healthy",
                    "totalBreakers", totalBreakers,
                    "openBreakers", openBreakers,
                    "halfOpenBreakers", halfOpenBreakers,
                    "closedBreakers", closedBreakers,
                    "hasOpenBreakers", openBreakers > 0
            );

            return ResponseEntity.ok(health);
        } catch (Exception e) {
            log.error("Failed to retrieve circuit breaker health status", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
