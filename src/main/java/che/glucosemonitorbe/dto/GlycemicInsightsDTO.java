package che.glucosemonitorbe.dto;

import java.util.List;
import java.util.Map;

/**
 * History-view summary. Counts and hour histograms are over top-level, non-dismissed events by effective
 * category; agreementRate is per detector category: confirmed-without-relabel / answered.
 */
public record GlycemicInsightsDTO(int days, Map<String, Integer> counts, Map<String, List<Integer>> hourHistogram,
                                  int noEffectCount14d, Map<String, Double> agreementRate) {}
