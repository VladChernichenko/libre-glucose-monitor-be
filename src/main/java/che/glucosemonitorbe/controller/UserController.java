package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.UserResponse;
import che.glucosemonitorbe.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "User", description = "User profile — currently authenticated user details")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "Get the currently authenticated user's profile")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "User profile returned"),
                    @ApiResponse(responseCode = "401", description = "Unauthorized") })
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        UserResponse user = userService.getUserByUsernameForResponse(username);
        return ResponseEntity.ok(user);
    }
}
