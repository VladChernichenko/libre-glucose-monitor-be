package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.UpdateUserInsulinPreferencesRequest;
import che.glucosemonitorbe.dto.UserInsulinPreferencesDTO;
import che.glucosemonitorbe.service.UserInsulinPreferencesService;
import che.glucosemonitorbe.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/user/insulin-preferences")
@RequiredArgsConstructor
public class UserInsulinPreferencesController {

    private final UserInsulinPreferencesService userInsulinPreferencesService;
    private final UserService userService;

    @GetMapping
    public ResponseEntity<UserInsulinPreferencesDTO> get(Authentication authentication) {
        UUID userId = userService.getUserByUsername(authentication.getName()).getId();
        return ResponseEntity.ok(userInsulinPreferencesService.getPreferences(userId));
    }

    @PutMapping
    public ResponseEntity<?> put(
            Authentication authentication,
            @Valid @RequestBody UpdateUserInsulinPreferencesRequest request) {
        try {
            UUID userId = userService.getUserByUsername(authentication.getName()).getId();
            UserInsulinPreferencesDTO saved = userInsulinPreferencesService.savePreferences(userId, request);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
