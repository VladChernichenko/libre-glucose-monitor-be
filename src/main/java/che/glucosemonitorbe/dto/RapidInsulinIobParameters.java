package che.glucosemonitorbe.dto;

/**
 * Parameters for OpenAPS exponential bolus IOB, derived from {@link InsulinCatalogDTO} rapid insulin.
 */
public record RapidInsulinIobParameters(double diaHours, double peakMinutes) {
}
