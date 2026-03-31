package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.ai.AiInsightService;
import che.glucosemonitorbe.dto.AiAnalysisRequest;
import che.glucosemonitorbe.dto.AiAnalysisResponse;
import che.glucosemonitorbe.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/ai-insights")
@RequiredArgsConstructor
public class AiInsightController {

    private final AiInsightService aiInsightService;
    private final UserService userService;

    @PostMapping("/retrospective")
    public ResponseEntity<AiAnalysisResponse> retrospective(
            Authentication authentication,
            @Valid @RequestBody(required = false) AiAnalysisRequest request
    ) {
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        int window = request == null || request.getWindowHours() == null ? 12 : request.getWindowHours();
        return ResponseEntity.ok(aiInsightService.analyzeRetrospective(userId, window));
    }
}
