package che.glucosemonitorbe.assistant.quicklog;

import java.time.LocalDateTime;

/** A parsed quick-log shorthand. {@code at} is the user's local wall time. */
public record QuickLogCommand(Kind kind, Double units, Double carbs, LocalDateTime at) {

    public enum Kind { RAPID, RAPID_WITH_MEAL, MEAL, FAST_SUGAR, LONG_ACTING }
}
