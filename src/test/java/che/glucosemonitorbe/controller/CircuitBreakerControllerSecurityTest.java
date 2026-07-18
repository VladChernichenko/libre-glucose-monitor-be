package che.glucosemonitorbe.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-9: circuit-breaker reset endpoints must require ADMIN.
 */
class CircuitBreakerControllerSecurityTest {

    @Test
    @DisplayName("BE-9: reset/{serviceName} requires ROLE_ADMIN")
    void resetOneRequiresAdmin() throws Exception {
        Method m = CircuitBreakerController.class.getMethod("resetCircuitBreaker", String.class);
        PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
        assertThat(pre).isNotNull();
        assertThat(pre.value()).isEqualTo("hasRole('ADMIN')");
    }

    @Test
    @DisplayName("BE-9: reset-all requires ROLE_ADMIN")
    void resetAllRequiresAdmin() throws Exception {
        Method m = CircuitBreakerController.class.getMethod("resetAllCircuitBreakers");
        PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
        assertThat(pre).isNotNull();
        assertThat(pre.value()).isEqualTo("hasRole('ADMIN')");
    }
}
