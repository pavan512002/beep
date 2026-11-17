package in.beep.jobs.tag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.*;
import in.beep.jobs.config.BeepProperties;

import java.util.Map;

/** Claude via the official Anthropic Java SDK, with structured (JSON-schema) output. */
public class AnthropicLlmClient implements LlmClient {
    private final String name;
    private final BeepProperties.Provider cfg;
    private AnthropicClient client;   // created on first use

    public AnthropicLlmClient(String name, BeepProperties.Provider cfg) {
        this.name = name;
        this.cfg = cfg;
    }

    @Override public String name() { return name; }
    @Override public BeepProperties.Provider config() { return cfg; }
    @Override public boolean hasKey() { return LlmClient.key(cfg) != null; }

    @Override
    public Result complete(String system, String user, Map<String, Object> schema) throws LlmException {
        var sb = JsonOutputFormat.Schema.builder();
        schema.forEach((k, v) -> sb.putAdditionalProperty(k, JsonValue.from(v)));

        var builder = MessageCreateParams.builder()
                .model(cfg.model())
                .maxTokens((long) cfg.maxOutputTokens())
                .system(system)
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(sb.build()).build())
                        .build())
                .addUserMessage(user);
        // Classification needs no extended thinking. How to switch it off differs by model.
        if (cfg.model().contains("haiku")) builder.thinking(ThinkingConfigDisabled.builder().build());
        else if (cfg.model().equals("claude-sonnet-5-5")) builder.thinking(ThinkingConfigBetweenTools.builder().build());

        Message msg;
        try {
            msg = client().messages().create(builder.build());
        } catch (AnthropicServiceException e) {
            int s = e.statusCode();
            if (s == 401 || s == 403 || s == 404) throw new LlmException(LlmException.Kind.SWITCH_PROVIDER, "HTTP " + s + ": " + e.getMessage(), 0);
            if (s == 429 || s >= 500) throw new LlmException(LlmException.Kind.RETRY, "HTTP " + s + ": " + e.getMessage(), 20_000);
            throw new LlmException(LlmException.Kind.SKIP_BATCH, "HTTP " + s + ": " + e.getMessage(), 0);
        } catch (RuntimeException e) {
            throw new LlmException(LlmException.Kind.RETRY, e.getMessage(), 20_000);
        }

        long in = msg.usage().inputTokens();
        long out = msg.usage().outputTokens();
        String stop = msg.stopReason().map(StopReason::toString).orElse("");
        if (msg.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
            return new Result(null, "refusal", in, out);
        }
        String json = msg.content().stream()
                .flatMap(b -> b.text().stream())
                .map(TextBlock::text)
                .reduce("", String::concat);
        boolean cutOff = msg.stopReason().map(r -> r.equals(StopReason.MAX_TOKENS)).orElse(false);
        return new Result(json, cutOff ? "length" : stop, in, out);
    }

    private synchronized AnthropicClient client() {
        if (client == null) client = AnthropicOkHttpClient.builder().apiKey(LlmClient.key(cfg)).build();
        return client;
    }
}
