package che.glucosemonitorbe.dto;

/**
 * Body for confirming an glycemic-event. Both amounts are optional: when supplied, the confirmed
 * event is backfilled as a real note (so the model uses real data); when omitted, the flag is simply
 * marked confirmed (the window is down-weighted in calibration).
 */
public record ConfirmGlycemicEventRequest(Double carbs, Double insulin) {}
