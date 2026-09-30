package che.glucosemonitorbe.dto;

/**
 * Body for confirming a glycemic event. {@code category} confirms or relabels (required for a PROVISIONAL
 * event; must match the event's direction). {@code carbs} may be backfilled only for a food category and
 * {@code insulin} only for an insulin category; either becomes a real note at the event's onset.
 */
public record ConfirmGlycemicEventRequest(String category, Double carbs, Double insulin) {}
