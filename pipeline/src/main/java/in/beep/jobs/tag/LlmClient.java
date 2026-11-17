package in.beep.jobs.tag;

import in.beep.jobs.config.BeepProperties;

import java.util.Map;

/** One LLM provider. Takes a system prompt, a user message and a JSON schema; returns JSON text. */
public interface LlmClient {

    /** Short provider name from application.yml, e.g. "groq". */
    String name();

    BeepProperties.Provider config();

    /** True when the API key environment variable is set. */
    boolean hasKey();

    Result complete(String system, String user, Map<String, Object> schema) throws LlmException;

    /** "provider:model", stored on each post so we know which model tagged it. */
    default String label() { return name() + ":" + config().model(); }

    /**
     * @param json         the model's JSON answer, or null if it refused
     * @param finishReason "stop", "length" (answer cut off), "refusal", ...
     */
    record Result(String json, String finishReason, long inputTokens, long outputTokens) {}

    static String key(BeepProperties.Provider cfg) {
        String k = cfg.keyEnv() == null ? null : System.getenv(cfg.keyEnv());
        return k == null || k.isBlank() ? null : k.trim();
    }
}
