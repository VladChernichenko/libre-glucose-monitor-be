package che.glucosemonitorbe.azt1d;

/** How a forecast fared against a scenario's rule. */
enum Outcome {
    PASS,
    FAIL,
    /** What actually happened did not meet the rule's precondition, so the rule says nothing. */
    NOT_APPLICABLE
}
