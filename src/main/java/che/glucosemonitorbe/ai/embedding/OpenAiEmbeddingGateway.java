package che.glucosemonitorbe.ai.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@link EmbeddingGateway} backed by the OpenAI-compatible {@code POST {url}/embeddings} API. */
@Slf4j
@Component
public class OpenAiEmbeddingGateway implements EmbeddingGateway {

    private static final int CONNECT_TIMEOUT_MS = 3000;

    private final RestClient client;
    private final String model;
    private final int dimensions;
    private final String apiKey;

    @Autowired
    public OpenAiEmbeddingGateway(
            RestClient.Builder builder,
            @Value("${app.ai.embeddings.url:https://api.openai.com/v1}") String url,
            @Value("${app.ai.embeddings.model:text-embedding-3-small}") String model,
            @Value("${app.ai.embeddings.dimensions:1536}") int dimensions,
            @Value("${app.ai.embeddings.api-key:}") String apiKey,
            @Value("${app.ai.embeddings.timeout-ms:8000}") int readTimeoutMs) {
        this(builder.requestFactory(timeoutFactory(readTimeoutMs)).baseUrl(url).build(),
                model, dimensions, apiKey);
    }

    /** Test seam: caller supplies a fully configured client. */
    OpenAiEmbeddingGateway(RestClient client, String model, int dimensions, String apiKey) {
        this.client = client;
        this.model = model;
        this.dimensions = dimensions;
        this.apiKey = apiKey;
    }

    private static SimpleClientHttpRequestFactory timeoutFactory(int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new EmbeddingUnavailableException("Embeddings API key is not configured");
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        JsonNode response;
        try {
            response = client.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(Map.of("model", model, "input", texts, "dimensions", dimensions))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new EmbeddingUnavailableException("Embedding request failed: " + e.getMessage(), e);
        }
        return parse(response, texts.size());
    }

    private List<float[]> parse(JsonNode response, int expected) {
        JsonNode data = response == null ? null : response.get("data");
        if (data == null || !data.isArray() || data.size() != expected) {
            throw new EmbeddingUnavailableException("Malformed embeddings response");
        }
        float[][] out = new float[expected][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode emb = item.get("embedding");
            if (index < 0 || index >= expected || out[index] != null || emb == null || !emb.isArray()
                    || emb.size() != dimensions) {
                throw new EmbeddingUnavailableException("Malformed embeddings response");
            }
            float[] v = new float[emb.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) emb.get(i).asDouble();
            }
            out[index] = v;
        }
        return new ArrayList<>(List.of(out));
    }
}
