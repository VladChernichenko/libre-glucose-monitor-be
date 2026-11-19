package che.glucosemonitorbe.azt1d;

/**
 * @param rule      the rule that failed ("flat-forecast", "no-rise", "drift"), or the kind's name when it passed
 * @param errorMmol absolute gap between forecast and reality at 120 minutes, NaN when that sample is missing
 */
record Verdict(Outcome outcome, String rule, double errorMmol, String detail) {}
