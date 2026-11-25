package che.glucosemonitorbe.controller;

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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The accept call as an older app sends it - JSON content type, empty body - must still apply ISF
 * only, through Spring's real body binding. Standalone MockMvc with a principal, as the other
 * controller tests.
 */
@ExtendWith(MockitoExtension.class)
class IsfMealWindowControllerHttpTest {

    private static final String ACCEPT = "/api/isf/meal-windows/suggestion/accept";

    @Mock private IsfMealWindowProfileService isfProfileService;
    @Mock private CarbRatioMealWindowProfileService carbRatioProfileService;
    @Mock private IsfMealWindowSuggestionService suggestionService;
    @Mock private UserService userService;

    private final UUID userId = UUID.randomUUID();
    private final UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken("alice", null);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        when(userService.getUserByUsername("alice")).thenReturn(UserDto.builder().id(userId).username("alice").build());
        mockMvc = MockMvcBuilders.standaloneSetup(new IsfMealWindowController(
                isfProfileService, carbRatioProfileService, suggestionService, userService)).build();
    }

    @Test
    void anEmptyJsonBodyAppliesIsfOnly() throws Exception {
        mockMvc.perform(post(ACCEPT).principal(auth).contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isOk());
        verify(suggestionService).accept(userId, false);
    }

    @Test
    void includeRatiosFalseAppliesIsfOnly() throws Exception {
        mockMvc.perform(post(ACCEPT).principal(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includeRatios\":false}"))
                .andExpect(status().isOk());
        verify(suggestionService).accept(userId, false);
    }

    @Test
    void includeRatiosTrueAppliesRatiosToo() throws Exception {
        mockMvc.perform(post(ACCEPT).principal(auth).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includeRatios\":true}"))
                .andExpect(status().isOk());
        verify(suggestionService).accept(userId, true);
    }
}
