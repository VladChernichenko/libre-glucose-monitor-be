package che.glucosemonitorbe.ai;

import che.glucosemonitorbe.domain.ClinicalKnowledgeChunk;
import che.glucosemonitorbe.repository.ClinicalKnowledgeChunkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagRetrieverServiceTest {

    @Mock ClinicalKnowledgeChunkRepository repo;
    @InjectMocks RagRetrieverService service;

    @Test
    @DisplayName("always includes GENERAL tag")
    void retrieve_alwaysIncludesGeneralTag() {
        when(repo.findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(anyList()))
                .thenReturn(List.of());

        AnalysisContext ctx = minimalContext(6.0, 0.0);
        service.retrieve(ctx);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(captor.capture());
        assertThat(captor.getValue()).contains("GENERAL");
    }

    @Test
    @DisplayName("adds HYPO tag when latest glucose < 3.9")
    void retrieve_addsHypoTag() {
        when(repo.findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(anyList()))
                .thenReturn(List.of());

        service.retrieve(minimalContext(3.5, 0.0));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(captor.capture());
        assertThat(captor.getValue()).contains("HYPO");
        assertThat(captor.getValue()).doesNotContain("HYPER");
    }

    @Test
    @DisplayName("adds HYPER tag when latest glucose > 10.0")
    void retrieve_addsHyperTag() {
        when(repo.findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(anyList()))
                .thenReturn(List.of());

        service.retrieve(minimalContext(12.0, 0.0));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(captor.capture());
        assertThat(captor.getValue()).contains("HYPER");
        assertThat(captor.getValue()).doesNotContain("HYPO");
    }

    @Test
    @DisplayName("adds RISING tag when delta > 2.0")
    void retrieve_addsRisingTag() {
        when(repo.findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(anyList()))
                .thenReturn(List.of());

        service.retrieve(minimalContext(8.0, 3.0));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(captor.capture());
        assertThat(captor.getValue()).contains("RISING");
        assertThat(captor.getValue()).doesNotContain("FALLING");
    }

    @Test
    @DisplayName("adds FALLING tag when delta < -2.0")
    void retrieve_addsFallingTag() {
        when(repo.findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(anyList()))
                .thenReturn(List.of());

        service.retrieve(minimalContext(7.0, -3.0));

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(captor.capture());
        assertThat(captor.getValue()).contains("FALLING");
        assertThat(captor.getValue()).doesNotContain("RISING");
    }

    @Test
    @DisplayName("adds IOB and COB tags when active values > 0")
    void retrieve_addsIobAndCobTags() {
        when(repo.findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(anyList()))
                .thenReturn(List.of());

        AnalysisContext ctx = AnalysisContext.builder()
                .latestGlucose(7.0)
                .deltaGlucose(0.0)
                .notes(List.of())
                .activeCob(20.0)
                .activeIob(1.5)
                .estimatedCorrectionUnits(0.0)
                .avgPreBolusPauseMinutes(null)
                .predictedGlucose2h(7.5)
                .build();
        service.retrieve(ctx);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(captor.capture());
        assertThat(captor.getValue()).contains("IOB", "COB");
    }

    @Test
    @DisplayName("result is limited to 8 chunks")
    void retrieve_limitsTo8Chunks() {
        List<ClinicalKnowledgeChunk> manyChunks = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            manyChunks.add(ClinicalKnowledgeChunk.builder()
                    .id(UUID.randomUUID())
                    .conditionTag("GENERAL")
                    .title("chunk " + i)
                    .content("content")
                    .active(true)
                    .build());
        }
        when(repo.findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(anyList()))
                .thenReturn(manyChunks);

        List<ClinicalKnowledgeChunk> result = service.retrieve(minimalContext(7.0, 0.0));

        assertThat(result).hasSize(8);
    }

    @Test
    @DisplayName("adds PRE_BOLUS tag when avgPreBolusPauseMinutes is present")
    void retrieve_addsPreBolusTagWhenPausePresent() {
        when(repo.findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(anyList()))
                .thenReturn(List.of());

        AnalysisContext ctx = AnalysisContext.builder()
                .latestGlucose(7.0)
                .deltaGlucose(0.0)
                .notes(List.of())
                .activeCob(0.0)
                .activeIob(0.0)
                .estimatedCorrectionUnits(0.0)
                .avgPreBolusPauseMinutes(20.0)
                .predictedGlucose2h(7.0)
                .build();
        service.retrieve(ctx);

        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).findByActiveTrueAndConditionTagInOrderByEvidenceLevelDescUpdatedAtDesc(captor.capture());
        assertThat(captor.getValue()).contains("PRE_BOLUS");
    }

    // ---- helpers ----

    private AnalysisContext minimalContext(double latest, double delta) {
        return AnalysisContext.builder()
                .latestGlucose(latest)
                .deltaGlucose(delta)
                .notes(List.of())
                .activeCob(0.0)
                .activeIob(0.0)
                .estimatedCorrectionUnits(0.0)
                .avgPreBolusPauseMinutes(null)
                .predictedGlucose2h(latest)
                .build();
    }
}
