package che.glucosemonitorbe.controller;

import che.glucosemonitorbe.dto.HypoEventDTO;
import che.glucosemonitorbe.dto.UserDto;
import che.glucosemonitorbe.entity.HypoEvent.State;
import che.glucosemonitorbe.service.FeatureToggleService;
import che.glucosemonitorbe.service.HypoEventService;
import che.glucosemonitorbe.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class HypoEventControllerTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID NOTE_ID = UUID.randomUUID();

    @Mock private HypoEventService hypoEventService;
    @Mock private FeatureToggleService featureToggleService;
    @Mock private UserService userService;

    private HypoEventController controller;
    private UUID authUserId;
    private UsernamePasswordAuthenticationToken auth;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        controller = new HypoEventController(hypoEventService, featureToggleService, userService);
        authUserId = UUID.randomUUID();
        // lenient: the disabled-feature test below returns before the controller resolves the user,
        // so this stub is legitimately unused in that case.
        lenient().when(userService.getUserByUsername("alice")).thenReturn(
                UserDto.builder().id(authUserId).username("alice").build());
        auth = new UsernamePasswordAuthenticationToken("alice", null);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/hypo-events returns the authenticated user's open events")
    void listReturnsOpenEvents() throws Exception {
        when(featureToggleService.isEnabled("hypo-rescue-logging-enabled")).thenReturn(true);
        when(hypoEventService.list(any(), eq(State.OPEN))).thenReturn(List.of(
                new HypoEventDTO(EVENT_ID, 3.4, "OPEN", null,
                        LocalDateTime.now(), null)));

        mockMvc.perform(get("/api/hypo-events").principal(auth).param("state", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("OPEN"))
                .andExpect(jsonPath("$[0].triggerGlucoseMmol").value(3.4));
    }

    @Test
    @DisplayName("POST /api/hypo-events/{id}/confirm passes the grams through to the service")
    void confirmPassesGramsThrough() throws Exception {
        when(featureToggleService.isEnabled("hypo-rescue-logging-enabled")).thenReturn(true);
        when(hypoEventService.confirm(any(), eq(EVENT_ID), eq(15.0))).thenReturn(
                new HypoEventDTO(EVENT_ID, 3.4, "CONFIRMED", NOTE_ID,
                        LocalDateTime.now(), LocalDateTime.now()));

        mockMvc.perform(post("/api/hypo-events/" + EVENT_ID + "/confirm")
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grams\":15.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CONFIRMED"));
    }

    @Test
    @DisplayName("Every endpoint 404s when the feature flag is disabled")
    void returnsNotFoundWhenTheFeatureIsDisabled() throws Exception {
        when(featureToggleService.isEnabled("hypo-rescue-logging-enabled")).thenReturn(false);

        mockMvc.perform(get("/api/hypo-events").principal(auth))
                .andExpect(status().isNotFound());
    }
}
