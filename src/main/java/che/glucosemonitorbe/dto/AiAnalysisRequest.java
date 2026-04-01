package che.glucosemonitorbe.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.util.List;

@Data
public class AiAnalysisRequest {
    @Min(1)
    @Max(72)
    private Integer windowHours = 12;

    private String followUpQuestion;

    private List<AiChatTurnDto> conversationTurns;

    private String model;

    @Min(512)
    @Max(32768)
    private Integer numCtx;

    @Data
    public static class AiChatTurnDto {
        private String role;
        private String content;
    }
}
