package che.glucosemonitorbe.azt1d;

/**
 * One real episode found in a subject's record: where it starts, the glucose there, and how far
 * ahead the forecast is checked. Times are the dataset's local wall clock encoded as UTC.
 */
record ScenarioEpisode(String subjectId, ScenarioKind kind, long startEpochMs, double startMmol, int horizonMin) {}
