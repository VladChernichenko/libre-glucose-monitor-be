package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.ai.embedding.EmbeddingGateway;
import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.repository.ClinicalKnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional
@SuppressWarnings({"resource", "null"})
class ClinicalRetrievalIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(che.glucosemonitorbe.TestPostgres.IMAGE)
                    .withDatabaseName("testdb").withUsername("test").withPassword("test");

    @Autowired private ClinicalKnowledgeChunkRepository repository;

    @BeforeEach
    void onlyTheSeededGeneralRow() {
        List<ClinicalKnowledgeChunk> rows = repository.findAll();
        assertThat(rows).extracting(ClinicalKnowledgeChunk::getConditionTag).containsExactly("GENERAL");
    }

    private static float[] axis(int i, double mix) {
        float[] v = new float[1536];
        v[i] = (float) (1 - mix);
        v[i + 1] = (float) mix;
        return v;
    }

    private ClinicalKnowledgeChunk save(String doc, int idx, String title, boolean active, float[] emb) {
        ClinicalKnowledgeChunk c = repository.saveAndFlush(ClinicalKnowledgeChunk.builder()
                .title(title).content(title + " passage").conditionTag("LIBRARY").active(active)
                .updatedAt(LocalDateTime.now()).sourceName("ADA").sourceTitle(title)
                .sourceUrl("https://doi.org/10.2337/" + doc).sourcePublishedAt(LocalDateTime.of(2025, 1, 1, 0, 0))
                .citation("ADA. Diabetes Care 2025").sourceDocId(doc).chunkIndex(idx).build());
        repository.setEmbedding(c.getId(), EmbeddingGateway.toLiteral(emb));
        return c;
    }

    @Test
    @DisplayName("retrieves the closer passage first, skips inactive rows, appends GENERAL")
    void closerPassageFirst() {
        ClinicalKnowledgeChunk hypo = save("dc25-S006", 0, "Hypoglycemia treatment", true, axis(0, 0.1));
        ClinicalKnowledgeChunk exercise = save("dc25-S005", 0, "Exercise and insulin", true, axis(10, 0.0));
        save("dc25-S006", 1, "Retired hypo passage", false, axis(0, 0.0));   // closest, but inactive

        EmbeddingGateway gw = mock(EmbeddingGateway.class);
        when(gw.embed(anyList())).thenReturn(List.of(axis(0, 0.0)));
        RagRetrieverService service = new RagRetrieverService(repository, gw);

        List<ClinicalKnowledgeChunk> r = service.retrieve("How do I treat a low?",
                AnalysisContext.builder().latestGlucose(3.5).notes(List.of()).build());

        assertThat(r).extracting(ClinicalKnowledgeChunk::getId)
                .startsWith(hypo.getId(), exercise.getId()).hasSize(3);
        assertThat(r.get(2).getConditionTag()).isEqualTo("GENERAL");
        assertThat(r.get(0).getCitation()).isEqualTo("ADA. Diabetes Care 2025");
        assertThat(r.get(0).getSourceDocId()).isEqualTo("dc25-S006");
        assertThat(r.get(0).getChunkIndex()).isZero();
    }

    @Test
    @DisplayName("source_doc_id + chunk_index is unique")
    void uniqueDocChunk() {
        save("dc25-S006", 0, "Hypoglycemia treatment", true, axis(0, 0.0));
        assertThatThrownBy(() -> save("dc25-S006", 0, "Duplicate", true, axis(1, 0.0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
