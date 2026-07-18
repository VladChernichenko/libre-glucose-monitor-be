package che.glucosemonitorbe.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

/**
 * Central API error mapping.
 *
 * <p>Design rules (BE-H5):
 * <ul>
 *   <li>Client-actionable errors return a specific 4xx with a safe, human-readable message.</li>
 *   <li>Server faults (5xx) are logged in full server-side and return a <em>generic</em> message plus
 *       the request's correlation id - internal exception text is never leaked to the caller.</li>
 * </ul>
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // -- 4xx: client-actionable ------------------------------------------------

    @ExceptionHandler(UsernameAlreadyExistsException.class)
    public ResponseEntity<CustomErrorResponse> handleUserExists(
            UsernameAlreadyExistsException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, HttpStatus.CONFLICT.getReasonPhrase(), ex.getMessage(), request);
    }

    @ExceptionHandler({BadCredentialsException.class, InvalidTokenException.class})
    public ResponseEntity<CustomErrorResponse> handleUnauthorized(
            RuntimeException ex, HttpServletRequest request) {
        // Keep the message generic for bad credentials; token errors may carry a safe reason.
        String message = ex instanceof BadCredentialsException ? "Invalid username or password" : ex.getMessage();
        return build(HttpStatus.UNAUTHORIZED, "Authentication failed", message, request);
    }

    @ExceptionHandler(UsernameNotFoundException.class)
    public ResponseEntity<CustomErrorResponse> handleUsernameNotFound(
            UsernameNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "User not found", ex.getMessage(), request);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<CustomErrorResponse> handleResourceNotFound(
            ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidConfigurationException.class)
    public ResponseEntity<CustomErrorResponse> handleInvalidConfiguration(
            InvalidConfigurationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid configuration", ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<CustomErrorResponse> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, "Forbidden", "You do not have permission to access this resource.", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<CustomErrorResponse> handleValidationException(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String errorMessage = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, "Validation failed", errorMessage, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<CustomErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String errorMessage = ex.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, "Validation failed", errorMessage, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<CustomErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Missing parameter",
                "Required parameter '" + ex.getParameterName() + "' is missing.", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<CustomErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid parameter",
                "Parameter '" + ex.getName() + "' has an invalid value.", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<CustomErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request", "Request body is missing or malformed.", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CustomErrorResponse> handleUploadTooLarge(
            MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large", "Uploaded file exceeds the maximum allowed size.", request);
    }

    /** Passes through the status code carried by the exception (e.g. 409 from service layer). */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<CustomErrorResponse> handleResponseStatus(
            ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String reason = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        return build(status, status.getReasonPhrase(), reason, request);
    }

    // -- 5xx: server / upstream faults -------------------------------------------

    @ExceptionHandler(ExternalServiceException.class)
    public ResponseEntity<CustomErrorResponse> handleExternalService(
            ExternalServiceException ex, HttpServletRequest request) {
        // Upstream dependency failed - log the cause, tell the client it's a gateway problem.
        log.warn("Upstream dependency failure on {} [{}]: {}",
                request.getRequestURI(), correlationId(), ex.getMessage(), ex);
        return build(HttpStatus.BAD_GATEWAY, "Upstream service unavailable",
                "A required upstream service is temporarily unavailable. Please try again later.", request);
    }

    /**
     * Streaming endpoints set Content-Type: application/x-ndjson; once the async context times out the
     * response is already committed, so we cannot write a JSON body - just set the status if possible.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException ex, HttpServletResponse response) {
        if (!response.isCommitted()) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        }
    }

    /**
     * The client closed the connection before the response finished (broken pipe / connection reset):
     * the app was backgrounded, a newer request superseded this one (e.g. pull-to-refresh), or a proxy
     * timed out. The socket is already gone, so there is nothing to write back - log quietly at DEBUG
     * and swallow. This is <em>not</em> a server fault: logging it as an ERROR 500 is misleading and
     * also fails a second time trying to serialise a body onto the dead connection.
     */
    @ExceptionHandler({AsyncRequestNotUsableException.class, ClientAbortException.class})
    public void handleClientDisconnect(Exception ex, HttpServletRequest request) {
        log.debug("Client disconnected before the response completed on {} [{}]: {}",
                request.getRequestURI(), correlationId(), ex.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<CustomErrorResponse> handleRuntimeException(
            RuntimeException ex, HttpServletRequest request) {
        return handleInternal(ex, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CustomErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        return handleInternal(ex, request);
    }

    // -- helpers -----------------------------------------------------------------

    /** Logs the full exception server-side and returns a generic body (no internal details leaked). */
    private ResponseEntity<CustomErrorResponse> handleInternal(Exception ex, HttpServletRequest request) {
        String correlationId = correlationId();
        log.error("Unhandled exception on {} [{}]", request.getRequestURI(), correlationId, ex);
        String message = "An internal error occurred."
                + (correlationId != null ? " Reference: " + correlationId : "");
        return build(HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                message, request);
    }

    private ResponseEntity<CustomErrorResponse> build(
            HttpStatus status, String error, String message, HttpServletRequest request) {
        CustomErrorResponse body = new CustomErrorResponse(
                status.value(), error, message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    private static String correlationId() {
        return MDC.get("correlationId");
    }
}
