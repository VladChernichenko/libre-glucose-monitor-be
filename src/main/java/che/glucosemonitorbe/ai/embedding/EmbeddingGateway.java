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

    /** The pgvector text form {@code [a,b,...]} of a vector, for native {@code CAST(? AS vector)} SQL. */
    static String toLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 9).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(v[i]));
        }
        return sb.append(']').toString();
    }
}
