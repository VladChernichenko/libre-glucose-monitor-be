package che.glucosemonitorbe.ai.embedding;

import java.util.List;

/** Turns texts into dense embedding vectors. */
public interface EmbeddingGateway {

    /**
     * Embeds the given texts.
     *
     * @return one vector per input text, in input order
     * @throws EmbeddingUnavailableException if the provider is not configured or cannot be reached
     */
    List<float[]> embed(List<String> texts);

    /** Dimensionality of the vectors returned by {@link #embed(List)}. */
    int dimensions();
}
