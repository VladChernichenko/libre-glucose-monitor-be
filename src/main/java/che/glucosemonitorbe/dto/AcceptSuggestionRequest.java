package che.glucosemonitorbe.dto;

/**
 * Body of the suggestion accept call. Carb ratios are applied only when the client sets
 * {@code includeRatios} to true, i.e. when it actually showed the ratio lines to the user.
 */
public record AcceptSuggestionRequest(Boolean includeRatios) {
}
