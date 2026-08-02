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
