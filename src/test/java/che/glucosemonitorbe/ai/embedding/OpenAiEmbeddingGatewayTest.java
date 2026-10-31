package che.glucosemonitorbe.ai.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiEmbeddingGatewayTest {

    private static final String URL = "https://embeddings.test/v1";

    private MockRestServiceServer server;

    private OpenAiEmbeddingGateway gateway(String apiKey, int dims) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new OpenAiEmbeddingGateway(builder, URL, "text-embedding-3-small", dims, apiKey);
    }

    @Test
    void sendsModelInputAndAuthorization() {
        OpenAiEmbeddingGateway gw = gateway("sk-test", 2);
        server.expect(requestTo(URL + "/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(jsonPath("$.model").value("text-embedding-3-small"))
                .andExpect(jsonPath("$.input[0]").value("a"))
                .andExpect(jsonPath("$.input[1]").value("b"))
                .andExpect(jsonPath("$.dimensions").value(2))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[0.1,0.2]},{"index":1,"embedding":[0.3,0.4]}]}
                        """, MediaType.APPLICATION_JSON));

        gw.embed(List.of("a", "b"));
        server.verify();
    }

    @Test
    void returnsVectorsInInputOrderEvenIfResponseIsShuffled() {
        OpenAiEmbeddingGateway gw = gateway("sk-test", 2);
        server.expect(requestTo(URL + "/embeddings"))
                .andRespond(withSuccess("""
                        {"data":[{"index":1,"embedding":[0.3,0.4]},{"index":0,"embedding":[0.1,0.2]}]}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> out = gw.embed(List.of("a", "b"));

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(out.get(1)).containsExactly(0.3f, 0.4f);
        assertThat(gw.dimensions()).isEqualTo(2);
    }

    @Test
    void serverErrorBecomesEmbeddingUnavailable() {
        OpenAiEmbeddingGateway gw = gateway("sk-test", 2);
        server.expect(requestTo(URL + "/embeddings")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> gw.embed(List.of("a"))).isInstanceOf(EmbeddingUnavailableException.class);
    }

    @Test
    void wrongDimensionIsRejected() {
        OpenAiEmbeddingGateway gw = gateway("sk-test", 3);
        server.expect(requestTo(URL + "/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gw.embed(List.of("a"))).isInstanceOf(EmbeddingUnavailableException.class);
    }

    @Test
    void missingApiKeyFailsWithoutCallingServer() {
        OpenAiEmbeddingGateway gw = gateway("  ", 2);

        assertThatThrownBy(() -> gw.embed(List.of("a"))).isInstanceOf(EmbeddingUnavailableException.class);
        server.verify(); // no expectations registered: any call would have failed
    }
}
