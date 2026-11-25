package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.AcceptSuggestionRequest;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.service.CarbRatioMealWindowProfileService;
import che.glucosemonitorbe.service.IsfMealWindowProfileService;
import che.glucosemonitorbe.service.IsfMealWindowSuggestionService;
import che.glucosemonitorbe.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IsfMealWindowControllerTest {

    @Mock private IsfMealWindowProfileService isfProfileService;
    @Mock private CarbRatioMealWindowProfileService carbRatioProfileService;
    @Mock private IsfMealWindowSuggestionService suggestionService;
    @Mock private UserService userService;
    @Mock private Authentication auth;

    private IsfMealWindowController controller;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new IsfMealWindowController(
                isfProfileService, carbRatioProfileService, suggestionService, userService);
        UserDto user = mock(UserDto.class);
        when(user.getId()).thenReturn(id);
        when(auth.getName()).thenReturn("alice");
        when(userService.getUserByUsername("alice")).thenReturn(user);
    }

    @Test
    void acceptWithoutBodyAppliesIsfOnly() {
        controller.acceptSuggestion(auth, null);
        verify(suggestionService).accept(id, false);
    }

    @Test
    void acceptWithIncludeRatiosPassesTrue() {
        controller.acceptSuggestion(auth, new AcceptSuggestionRequest(true));
        verify(suggestionService).accept(id, true);
    }

    @Test
    void nothingToApplyIsAConflict() {
        doThrow(new IllegalStateException("Nothing to apply")).when(suggestionService).accept(id, false);
        assertThatThrownBy(() -> controller.acceptSuggestion(auth, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }
}
