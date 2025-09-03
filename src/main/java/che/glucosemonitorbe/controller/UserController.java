package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.UserResponse;
import che.glucosemonitorbe.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@CrossOrigin(origins = {"https://libre-glucose-monitor-frontend.onrender.com", "https://libre-glucose-monitor.onrender.com", "http://localhost:3000", "http://127.0.0.1:3000"})
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(Authentication authentication) {
        String username = authentication.getName();
        UserResponse user = userService.getUserByUsernameForResponse(username);
        return ResponseEntity.ok(user);
    }
}
