package che.glucosemonitorbe.dto;

/**
 * Body for confirming a hypo event: the grams of fast-acting carb the user actually took.
 * Required - a confirm with no amount would log a rescue of unknown size.
 */
public record ConfirmHypoEventRequest(Double grams) {}
