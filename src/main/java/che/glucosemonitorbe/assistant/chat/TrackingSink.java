package che.glucosemonitorbe.assistant.chat;

import java.util.function.Consumer;

/**
 * Wraps the reply stream and remembers when a send failed (the client went away), so later LLM calls can
 * be skipped. The failure is still rethrown to the caller.
 */
final class TrackingSink implements Consumer<AssistantEvent> {

    private final Consumer<AssistantEvent> delegate;
    private volatile boolean gone;

    TrackingSink(Consumer<AssistantEvent> delegate) {
        this.delegate = delegate;
    }

    @Override
    public void accept(AssistantEvent event) {
        try {
            delegate.accept(event);
        } catch (RuntimeException e) {
            gone = true;
            throw e;
        }
    }

    /** True once a send failed or the stream thread was interrupted. */
    boolean gone() {
        return gone || Thread.currentThread().isInterrupted();
    }
}
