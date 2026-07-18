package che.glucosemonitorbe.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IsfMealWindowSuggestionDTO {

    /** Whether the client should show the morning banner right now. */
    private boolean show;

    /** Reason the banner is hidden (for debugging / analytics); null when show=true. */
    private String suppressReason;

    private boolean twinReady;
    private boolean twinApplied;

    private List<WindowProposal> windows;

    private Integer historyDays;
    private Double minWeightedSamples;
    private Integer cadenceDays;
    private LocalDateTime nextEligibleAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WindowProposal {
        private String mealWindow;
        private Double proposedIsf;
        private Double currentIsf;
        private boolean hasData;
        private Double weightedSamples;
    }
}
