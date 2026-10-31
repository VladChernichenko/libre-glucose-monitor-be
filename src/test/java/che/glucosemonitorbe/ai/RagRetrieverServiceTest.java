package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.ai.embedding.EmbeddingGateway;
import che.glucosemonitorbe.ai.embedding.EmbeddingUnavailableException;
import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.repository.ClinicalKnowledgeChunkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagRetrieverServiceTest {

    @Mock ClinicalKnowledgeChunkRepository repo;
    @Mock EmbeddingGateway embeddings;
    @InjectMocks RagRetrieverService service;

    private static ClinicalKnowledgeChunk chunk(String title, String tag) {
        return ClinicalKnowledgeChunk.builder().id(UUID.randomUUID()).title(title).content(title + " text")
                .conditionTag(tag).active(true).build();
    }

    private static AnalysisContext ctx(double glucose, double delta, Double iob, Double cob) {
        return AnalysisContext.builder().userId(UUID.randomUUID()).latestGlucose(glucose).deltaGlucose(delta)
                .activeIob(iob).activeCob(cob).notes(List.of()).build();
    }

    @Test
    @DisplayName("embeds question + situation, cosine top 4 among active, then the GENERAL row")
    void topFourPlusGeneral() {
        ClinicalKnowledgeChunk general = chunk("General safety disclaimer", "GENERAL");
        List<ClinicalKnowledgeChunk> top = List.of(chunk("a", "LIB"), chunk("b", "LIB"), chunk("c", "LIB"),
                chunk("d", "LIB"));
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[]{0.5f, 0.25f}));
        when(repo.findNearestActive("[0.5,0.25]", RagRetrieverService.TOP_K)).thenReturn(top);
        when(repo.findFirstByActiveTrueAndConditionTagOrderByUpdatedAtDesc("GENERAL")).thenReturn(Optional.of(general));

        List<ClinicalKnowledgeChunk> r = service.retrieve("How do I treat a low?", ctx(3.4, -2.5, 1.2, 0.0));

        assertThat(RagRetrieverService.TOP_K).isEqualTo(4);
        assertThat(r).containsExactly(top.get(0), top.get(1), top.get(2), top.get(3), general);
        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
        verify(embeddings).embed(texts.capture());
        assertThat(texts.getValue()).hasSize(1);
        String text = texts.getValue().get(0);
        assertThat(text).startsWith("How do I treat a low?");
        assertThat(text).contains("low glucose").contains("falling").contains("insulin on board");
        assertThat(text).doesNotContain("carbs on board");
    }

    @Test
    @DisplayName("situation rendering never carries the user id")
    void situationHasNoUserId() {
        AnalysisContext c = ctx(12.5, 2.5, 0.0, 30.0);
        String s = RagRetrieverService.situation(c);
        assertThat(s).contains("high glucose").contains("rising").contains("carbs on board");
        assertThat(s).doesNotContain(c.getUserId().toString());
    }

    @Test
    @DisplayName("without a fresh reading (glucose 0) the situation says so")
    void noReading() {
        assertThat(RagRetrieverService.situation(ctx(0.0, 0.0, null, null))).contains("no fresh glucose reading");
    }

    @Test
    @DisplayName("embedding unavailable returns only the GENERAL row")
    void embeddingDownReturnsGeneralOnly() {
        ClinicalKnowledgeChunk general = chunk("General safety disclaimer", "GENERAL");
        when(embeddings.embed(anyList())).thenThrow(new EmbeddingUnavailableException("down"));
        when(repo.findFirstByActiveTrueAndConditionTagOrderByUpdatedAtDesc("GENERAL")).thenReturn(Optional.of(general));

        assertThat(service.retrieve("why high?", ctx(9, 0, null, null))).containsExactly(general);
        verify(repo, never()).findNearestActive(anyString(), anyInt());
    }

    @Test
    @DisplayName("no GENERAL row: passages only; nothing at all: empty list")
    void missingGeneral() {
        ClinicalKnowledgeChunk a = chunk("a", "LIB");
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[]{1f}));
        lenient().when(repo.findNearestActive(eq("[1.0]"), anyInt())).thenReturn(List.of(a));
        when(repo.findFirstByActiveTrueAndConditionTagOrderByUpdatedAtDesc("GENERAL")).thenReturn(Optional.empty());

        assertThat(service.retrieve("q", ctx(6, 0, null, null))).containsExactly(a);
    }

    @Test
    @DisplayName("a blank question still retrieves by the situation")
    void blankQuestion() {
        when(embeddings.embed(anyList())).thenReturn(List.of(new float[]{1f}));
        when(repo.findNearestActive(anyString(), anyInt())).thenReturn(List.of());
        when(repo.findFirstByActiveTrueAndConditionTagOrderByUpdatedAtDesc("GENERAL")).thenReturn(Optional.empty());

        assertThat(service.retrieve(null, ctx(6, 0, null, null))).isEmpty();
        ArgumentCaptor<List<String>> texts = ArgumentCaptor.forClass(List.class);
        verify(embeddings).embed(texts.capture());
        assertThat(texts.getValue().get(0)).startsWith("Current situation");
    }
}
