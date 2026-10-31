package che.glucosemonitorbe.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Errors thrown before an NDJSON stream starts (e.g. photo storage not configured on the assistant
 * endpoint) must reach the client with their real status and a JSON body, not a 406; and requests
 * for removed endpoints map to a plain 404.
 */
class GlobalExceptionHandlerStreamingTest {

    @RestController
    static class StubController {
        @PostMapping(path = "/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
        public String stream() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Photo storage is not configured");
        }

        @GetMapping("/gone")
        public String gone() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "api/experiments");
        }
    }

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StubController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void responseStatusOnNdjsonEndpoint_keepsStatusAndWritesJsonBody() throws Exception {
        mockMvc.perform(post("/stream").accept(MediaType.APPLICATION_NDJSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Photo storage is not configured"));
    }

    @Test
    void noResourceFound_returns404() throws Exception {
        mockMvc.perform(get("/gone"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
