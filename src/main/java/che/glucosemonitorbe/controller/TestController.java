package che.glucosemonitorbe.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Debug", description = "Development health-check endpoints")
@RestController
@RequestMapping("/api/test")
public class TestController {
    
    @Operation(summary = "Application health check")
    @ApiResponse(responseCode = "200", description = "Application is running")
    @GetMapping("/health")
    public String health() {
        return "Application is running!";
    }
    
    @GetMapping("/status")
    public String status() {
        return "Status: OK - Database connection should be established";
    }
}
