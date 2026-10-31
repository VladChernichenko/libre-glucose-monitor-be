package che.glucosemonitorbe.dto;

import lombok.Data;

/** Holder for the chat-turn DTO the assistant passes to the LLM prompt builder. */
public class AiAnalysisRequest {

    @Data
    public static class AiChatTurnDto {
        private String role;
        private String content;
    }
}
