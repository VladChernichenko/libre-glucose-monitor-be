package che.glucosemonitorbe.assistant.quicklog;

/** A quick-log command that parsed but must not be written (out-of-range dose or carbs). */
public class QuickLogRejectedException extends RuntimeException {
    public QuickLogRejectedException(String reason) {
        super(reason);
    }
}
