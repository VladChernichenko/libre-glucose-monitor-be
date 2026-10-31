package che.glucosemonitorbe.ai.embedding;

/** Thrown when embeddings cannot be produced (missing key, provider error, malformed response). */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message) {
        super(message);
    }

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
