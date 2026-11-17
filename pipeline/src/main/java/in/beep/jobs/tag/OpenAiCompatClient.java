package in.beep.jobs.tag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.beep.jobs.config.BeepProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Any provider that speaks the OpenAI chat-completions API: Groq, Gemini, OpenRouter, OpenCode Zen...
 * Uses strict JSON-schema output, so the answer always has the fields and allowed values we expect.
 */
public class OpenAiCompatClient implements LlmClient {
    private static final long DEFAULT_RETRY_MS = 20_000;

    private final String name;
    private final BeepProperties.Provider cfg;
    private final ObjectMapper mapper;
    private final RestClient http;

    public OpenAiCompatClient(String name, BeepProperties.Provider cfg, ObjectMapper mapper) {
        this.name = name;
        this.cfg = cfg;
        this.mapper = mapper;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(180));
        this.http = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(cfg.baseUrl().replaceAll("/+$", ""))
                .build();
    }

    @Override public String name() { return name; }
    @Override public BeepProperties.Provider config() { return cfg; }
    @Override public boolean hasKey() { return LlmClient.key(cfg) != null; }

    @Override
    public Result complete(String system, String user, Map<String, Object> schema) throws LlmException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.model());
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        body.put("temperature", 0);
        body.put("max_tokens", cfg.maxOutputTokens());
        if (cfg.reasoningEffort() != null && !cfg.reasoningEffort().isBlank()) {
            body.put("reasoning_effort", cfg.reasoningEffort());
        }
        body.put("response_format", Map.of(
                "type", "json_schema",
                "json_schema", Map.of("name", "job_tags", "strict", true, "schema", schema)));

        String response;
        try {
            response = http.post().uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + LlmClient.key(cfg))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException e) {
            throw classify(e.getStatusCode().value(), e.getResponseBodyAsString(), e.getResponseHeaders());
        } catch (HttpServerErrorException e) {
            throw new LlmException(LlmException.Kind.RETRY, name + " server error " + e.getStatusCode().value(), DEFAULT_RETRY_MS);
        } catch (ResourceAccessException e) {
            throw new LlmException(LlmException.Kind.RETRY, name + " network error: " + e.getMessage(), DEFAULT_RETRY_MS);
        }

        try {
            JsonNode n = mapper.readTree(response);
            JsonNode choice = n.path("choices").path(0);
            JsonNode usage = n.path("usage");
            String content = choice.path("message").path("content").asText(null);
            String refusal = choice.path("message").path("refusal").asText(null);
            String finish = refusal != null ? "refusal" : choice.path("finish_reason").asText("");
            return new Result(refusal != null ? null : content, finish,
                    usage.path("prompt_tokens").asLong(), usage.path("completion_tokens").asLong());
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.SKIP_BATCH, name + " returned unreadable JSON: " + e.getMessage(), 0);
        }
    }

    /** Turns an HTTP error into what the tagger should do next. */
    static LlmException classify(int status, String body, HttpHeaders headers) {
        String msg = errorMessage(body);
        String shortMsg = "HTTP " + status + ": " + (msg.length() > 300 ? msg.substring(0, 300) + "…" : msg);
        if (status == 429) {
            String lower = msg.toLowerCase();
            boolean daily = lower.contains("per day") || lower.contains("perday") || lower.contains("(tpd)")
                    || lower.contains("(rpd)") || lower.contains("daily");
            if (daily) return new LlmException(LlmException.Kind.SWITCH_PROVIDER, "daily limit reached – " + shortMsg, 0);
            return new LlmException(LlmException.Kind.RETRY, "rate limited – " + shortMsg, retryAfterMs(headers));
        }
        if (status == 401 || status == 403) {
            return new LlmException(LlmException.Kind.SWITCH_PROVIDER, "key rejected – " + shortMsg, 0);
        }
        if (status == 404) {   // model name wrong or retired: no point trying again with this provider
            return new LlmException(LlmException.Kind.SWITCH_PROVIDER, "model not found – " + shortMsg, 0);
        }
        return new LlmException(LlmException.Kind.SKIP_BATCH, shortMsg, 0);
    }

    /** Pulls error.message out of {"error":{...}} or Gemini's [{"error":{...}}]; falls back to the raw body. */
    private static String errorMessage(String body) {
        if (body == null) return "";
        try {
            JsonNode n = new ObjectMapper().readTree(body);
            if (n.isArray()) n = n.path(0);
            String m = n.path("error").path("message").asText("");
            return m.isEmpty() ? body.strip() : m;
        } catch (Exception e) {
            return body.strip();
        }
    }

    private static long retryAfterMs(HttpHeaders headers) {
        String v = headers == null ? null : headers.getFirst("retry-after");
        if (v == null) return DEFAULT_RETRY_MS;
        try {
            return (long) (Double.parseDouble(v.trim()) * 1000) + 500;
        } catch (NumberFormatException e) {
            return DEFAULT_RETRY_MS;
        }
    }
}
