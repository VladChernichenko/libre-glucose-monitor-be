package che.glucosemonitorbe.service.nutrition;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NutritionVisionServiceTest {

    private static final String OLLAMA_URL = "http://localhost:11434/api/generate";

    // 1×1 white pixel JPEG — smallest valid JPEG for image-in-request tests
    private static final byte[] TINY_JPEG = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8U"
            + "HRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgN"
            + "DRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy"
            + "MjL/wAARCAABAAEDASIAAhEBAxEB/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAA"
            + "AAAAAAAAAAAAAP/EABQBAQAAAAAAAAAAAAAAAAAAAAD/xAAUEQEAAAAAAAAAAAAAAAAAAAAA"
            + "/9oADAMBAAIRAxEAPwCwABmX/9k="
    );

    private NutritionVisionService service;
    private NutritionEnrichmentService enrichmentService;
    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        enrichmentService = mock(NutritionEnrichmentService.class);
        service = new NutritionVisionService(objectMapper, enrichmentService);

        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "ollamaEnabled", true);
        ReflectionTestUtils.setField(service, "ollamaUrl", OLLAMA_URL);
        ReflectionTestUtils.setField(service, "visionModel", "llava:latest");
        ReflectionTestUtils.setField(service, "ollamaApiKey", "");
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    void fullJsonResponse_mapsAllFields() throws Exception {
        String llavaJson = objectMapper.writeValueAsString(Map.of(
                "foods", List.of("rice", "chicken breast", "broccoli"),
                "totalCarbs", 45.5,
                "fiber", 3.2,
                "protein", 22.1,
                "fat", 8.5,
                "estimatedGi", 58.0,
                "glycemicLoad", 24.4,
                "absorptionSpeedClass", "MEDIUM"
        ));
        stubOllama(llavaJson);

        NutritionSnapshot result = service.analyzeImage(jpegFile());

        mockServer.verify();
        assertEquals("VISION_LLM", result.getSource());
        assertEquals("GI_GL_ENHANCED", result.getAbsorptionMode());
        assertEquals(0.75, result.getConfidence());
        assertEquals(45.5, result.getTotalCarbs());
        assertEquals(3.2, result.getFiber());
        assertEquals(22.1, result.getProtein());
        assertEquals(8.5, result.getFat());
        assertEquals(58.0, result.getEstimatedGi());
        assertEquals(24.4, result.getGlycemicLoad());
        assertEquals("MEDIUM", result.getAbsorptionSpeedClass());
        assertEquals(List.of("rice", "chicken breast", "broccoli"), result.getNormalizedFoods());
    }

    @Test
    void requestContainsBase64Image() throws Exception {
        String expectedBase64 = Base64.getEncoder().encodeToString(TINY_JPEG);
        String responseJson = "{\"foods\":[\"apple\"],\"totalCarbs\":15,\"fiber\":2,\"protein\":0,\"fat\":0,\"estimatedGi\":38,\"glycemicLoad\":5,\"absorptionSpeedClass\":\"SLOW\"}";

        mockServer.expect(requestTo(OLLAMA_URL))
                  .andExpect(method(HttpMethod.POST))
                  .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                  .andExpect(content().string(org.hamcrest.Matchers.containsString(expectedBase64)))
                  .andRespond(withSuccess(ollamaBody(responseJson), MediaType.APPLICATION_JSON));

        NutritionSnapshot result = service.analyzeImage(jpegFile());

        mockServer.verify();
        assertNotNull(result);
        assertEquals(List.of("apple"), result.getNormalizedFoods());
    }

    // ── llava output quirks ───────────────────────────────────────────────────

    @Test
    void llavaWrapsJsonInMarkdown_extractedCorrectly() throws Exception {
        String markdownWrapped = "Sure! Here's the nutrition info:\n```json\n"
                + "{\"foods\":[\"pasta\"],\"totalCarbs\":60,\"fiber\":2,\"protein\":8,\"fat\":3,\"estimatedGi\":65,\"glycemicLoad\":38,\"absorptionSpeedClass\":\"FAST\"}"
                + "\n```";
        stubOllama(markdownWrapped);

        NutritionSnapshot result = service.analyzeImage(jpegFile());

        assertEquals("VISION_LLM", result.getSource());
        assertEquals(60.0, result.getTotalCarbs());
        assertEquals(List.of("pasta"), result.getNormalizedFoods());
    }

    @Test
    void llavaReturnsFoodsButNoCarbs_fallsBackToEnrichment() throws Exception {
        stubOllama("{\"foods\":[\"oatmeal\",\"banana\"],\"totalCarbs\":0,\"fiber\":0,\"protein\":0,\"fat\":0,\"estimatedGi\":0,\"glycemicLoad\":0,\"absorptionSpeedClass\":\"DEFAULT\"}");
        NutritionSnapshot enriched = NutritionSnapshot.builder()
                .source("KEYWORD_GI").totalCarbs(55.0).absorptionMode("GI_GL_ENHANCED").build();
        when(enrichmentService.enrichFromText("oatmeal, banana", "", null)).thenReturn(enriched);

        NutritionSnapshot result = service.analyzeImage(jpegFile());

        verify(enrichmentService).enrichFromText("oatmeal, banana", "", null);
        assertEquals("KEYWORD_GI", result.getSource());
        assertEquals(55.0, result.getTotalCarbs());
    }

    @Test
    void llavaReturnsNoGi_absorptionModeIsDefaultDecay() throws Exception {
        // carbs > 0 so no enrichment fallback; estimatedGi = 0 → DEFAULT_DECAY
        stubOllama("{\"foods\":[\"lettuce\"],\"totalCarbs\":2,\"fiber\":1,\"protein\":0,\"fat\":0,\"estimatedGi\":0,\"glycemicLoad\":0,\"absorptionSpeedClass\":\"DEFAULT\"}");

        NutritionSnapshot result = service.analyzeImage(jpegFile());

        assertEquals("DEFAULT_DECAY", result.getAbsorptionMode());
        assertNull(result.getEstimatedGi());
        assertNull(result.getGlycemicLoad());
        assertEquals(2.0, result.getTotalCarbs());
    }

    @Test
    void llavaReturnsMissingFields_defaultsToZero() throws Exception {
        // include totalCarbs > 0 so no enrichment fallback; other fields absent → 0.0
        stubOllama("{\"foods\":[\"unknown food\"],\"totalCarbs\":10}");

        NutritionSnapshot result = service.analyzeImage(jpegFile());

        assertEquals("VISION_LLM", result.getSource());
        assertEquals(10.0, result.getTotalCarbs());
        assertEquals(0.0, result.getFiber());
        assertEquals("DEFAULT", result.getAbsorptionSpeedClass());
    }

    @Test
    void llavaReturnsEmptyJson_returnsDefaultDecaySnapshot() throws Exception {
        stubOllama("{}");

        NutritionSnapshot result = service.analyzeImage(jpegFile());

        assertEquals("VISION_LLM", result.getSource());
        assertEquals("DEFAULT_DECAY", result.getAbsorptionMode());
        assertTrue(result.getNormalizedFoods().isEmpty());
    }

    @Test
    void llavaReturnsGarbage_fallsBackToVisionFallbackSnapshot() {
        mockServer.expect(requestTo(OLLAMA_URL))
                  .andRespond(withSuccess("{\"response\":\"not json at all !!!\"}",
                          MediaType.APPLICATION_JSON));

        NutritionSnapshot result = service.analyzeImage(jpegFile());

        // extractJson returns "{}" on garbage, so foods=empty, carbs=0 → DEFAULT_DECAY VISION_LLM
        assertEquals("VISION_LLM", result.getSource());
        assertTrue(result.getNormalizedFoods().isEmpty());
    }

    // ── failure / fallback ────────────────────────────────────────────────────

    @Test
    void ollamaReturns500_returnsFallbackSnapshot() {
        mockServer.expect(requestTo(OLLAMA_URL))
                  .andRespond(withServerError());

        NutritionSnapshot result = service.analyzeImage(jpegFile());

        assertEquals("VISION_FALLBACK", result.getSource());
        assertEquals("DEFAULT_DECAY", result.getAbsorptionMode());
        assertEquals(0.1, result.getConfidence());
        assertEquals(0.0, result.getTotalCarbs());
    }

    @Test
    void ollamaDisabled_returnsFallbackSnapshot() {
        ReflectionTestUtils.setField(service, "ollamaEnabled", false);

        NutritionSnapshot result = service.analyzeImage(jpegFile());

        assertEquals("VISION_FALLBACK", result.getSource());
        verifyNoInteractions(enrichmentService);
    }

    @Test
    void emptyPhoto_ollamaFailure_returnsFallbackSnapshot() throws Exception {
        // Empty bytes base64-encode fine and reach Ollama; simulate server error
        mockServer.expect(requestTo(OLLAMA_URL))
                  .andRespond(withServerError());

        NutritionSnapshot result = service.analyzeImage(new MockMultipartFile("photo", new byte[0]));

        assertEquals("VISION_FALLBACK", result.getSource());
        mockServer.verify();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubOllama(String llavaResponseContent) throws Exception {
        mockServer.expect(requestTo(OLLAMA_URL))
                  .andExpect(method(HttpMethod.POST))
                  .andRespond(withSuccess(ollamaBody(llavaResponseContent), MediaType.APPLICATION_JSON));
    }

    private String ollamaBody(String content) throws Exception {
        // Ollama wraps the model output as a JSON string inside the "response" field
        return objectMapper.createObjectNode()
                .put("model", "llava:latest")
                .put("response", content)   // content is a string value, properly escaped
                .put("done", true)
                .put("prompt_eval_count", 120)
                .put("eval_count", 80)
                .toString();
    }

    private MockMultipartFile jpegFile() {
        return new MockMultipartFile("photo", "meal.jpg", "image/jpeg", TINY_JPEG);
    }
}
