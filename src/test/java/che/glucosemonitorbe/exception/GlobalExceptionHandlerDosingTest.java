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
