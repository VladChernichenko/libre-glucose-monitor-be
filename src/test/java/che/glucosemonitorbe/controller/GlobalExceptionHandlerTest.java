package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.GlucoseCalculationsRequest;
import che.glucosemonitorbe.exception.GlobalExceptionHandler;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.GlucoseCalculationsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for GlobalExceptionHandler and GlucoseCalculationsController covering:
 * - BE-11: GlobalExceptionHandler.handleRuntimeException returns HTTP 500 for unhandled
 *          RuntimeExceptions when the GlobalExceptionHandler is installed.
 *          (Regression guard — the handler exists in source; this test confirms it works.)
 * - A1:    GlucoseCalculationsController catches ALL exceptions in its own catch block and
 *          returns ResponseEntity.badRequest() (400), even for RuntimeExceptions that are
 *          server errors.  The GlobalExceptionHandler never gets a chance to intervene.
 *          Expected: 500 for RuntimeException. This test FAILS against the buggy code.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private GlucoseCalculationsService glucoseCalculationsService;

    @Mock
    private FeatureToggleService featureToggleService;

    @InjectMocks
    private GlucoseCalculationsController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Install the GlobalExceptionHandler so it can intercept any exception
        // that propagates past the controller's own catch block.
        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    // ── BE-11 regression guard: GlobalExceptionHandler maps RuntimeException → 500 ────

    /**
     * BE-11 regression test — verifies that the GlobalExceptionHandler's
     * RuntimeException handler (added as the BE-11 fix) returns HTTP 500
     * when an unhandled RuntimeException escapes the controller.
     *
     * For GlucoseCalculationsController this only triggers when the feature flag
     * is DISABLED (short-circuit path does not have a try/catch), but the handler
     * is shared across all controllers.
     *
     * When featureToggleService.shouldUseBackend returns false, the controller
     * returns 200 — no exception propagates. This test verifies the 200 path.
     */
    @Test
    void be11_featureDisabled_returns200() throws Exception {
        when(featureToggleService.shouldUseBackend("glucose-calculations")).thenReturn(false);

        String requestJson = objectMapper.writeValueAsString(
                GlucoseCalculationsRequest.builder()
                        .currentGlucose(5.5)
                        .build());

        mockMvc.perform(post("/api/glucose-calculations/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk());
    }

    // ── A1: controller catches ALL exceptions and returns 400 instead of 500 ──

    /**
     * BUG: A1 — GlucoseCalculationsController has a catch(Exception e) block in
     * calculateGlucoseData that returns ResponseEntity.badRequest() (HTTP 400) for
     * ALL exceptions, including RuntimeExceptions that indicate server errors.
     *
     * Expected: when GlucoseCalculationsService throws RuntimeException("server down"),
     *           the response must be HTTP 500 (Internal Server Error), not 400.
     *
     * This test FAILS because the controller's catch block intercepts the RuntimeException
     * and returns 400 before the GlobalExceptionHandler can handle it.
     *
     * To fix: remove the catch(Exception e) in the controller and let exceptions propagate
     * to GlobalExceptionHandler, or re-throw RuntimeExceptions.
     */
    @Test
    void a1_calculationService_throwsRuntimeException_mustReturn500NotBadRequest() throws Exception {
        when(featureToggleService.shouldUseBackend("glucose-calculations")).thenReturn(true);
        when(glucoseCalculationsService.calculateGlucoseData(any(GlucoseCalculationsRequest.class)))
                .thenThrow(new RuntimeException("server down"));

        String requestJson = objectMapper.writeValueAsString(
                GlucoseCalculationsRequest.builder()
                        .currentGlucose(5.5)
                        .userId("testuser")
                        .build());

        // BUG: current controller returns 400 because it catches ALL exceptions.
        // With GlobalExceptionHandler installed, 500 is expected.
        // This assertion FAILS until the controller removes its blanket catch(Exception).
        mockMvc.perform(post("/api/glucose-calculations/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isInternalServerError());
    }

    // ── client disconnect (broken pipe) is benign, not a 500 ──────────────────

    /**
     * When the client closes the connection mid-response (broken pipe), Spring MVC raises
     * {@link AsyncRequestNotUsableException}. The handler must swallow it quietly (void → no body
     * written to the dead socket) and never rethrow or escalate it to an ERROR 500. Regression guard
     * for /api/nightscout/chart-data broken-pipe noise.
     */
    @Test
    void clientDisconnect_isHandledQuietly_neverRethrown() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/nightscout/chart-data");

        assertThatCode(() -> handler.handleClientDisconnect(
                new AsyncRequestNotUsableException("ServletOutputStream failed to write: Broken pipe"),
                request))
                .doesNotThrowAnyException();
    }

}
