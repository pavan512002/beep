package in.beep.jobs.tag;

/** An LLM call failed. {@link Kind} tells the tagger what to do next. */
public class LlmException extends Exception {

    public enum Kind {
        /** Per-minute limit, overload or network blip: wait and retry the same provider. */
        RETRY,
        /** Daily quota used up, or the key was rejected: move on to the next provider. */
        SWITCH_PROVIDER,
        /** This request can't succeed (bad request, too large): skip the batch, retry next run. */
        SKIP_BATCH
    }

    public final Kind kind;
    public final long retryAfterMs;

    public LlmException(Kind kind, String message, long retryAfterMs) {
        super(message);
        this.kind = kind;
        this.retryAfterMs = retryAfterMs;
    }
}
