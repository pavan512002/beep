package in.beep.jobs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/** All settings from application.yml under "beep". */
@ConfigurationProperties(prefix = "beep")
public record BeepProperties(
        String root,
        String userAgent,
        int maxAgeDays,
        int staleAfterDays,
        int descriptionMaxChars,
        Dedup dedup,
        Tagging tagging,
        Sources sources) {

    public record Dedup(double titleSimilarity) {}

    public record Tagging(
            boolean enabled,
            String promptVersion,
            int batchSize,             // posts per LLM request
            int maxPostsPerRun,
            List<String> use,          // provider names, tried in this order
            Map<String, Provider> providers) {}

    /** One LLM provider. type "openai" = any OpenAI-compatible API (Groq, Gemini, OpenRouter...). */
    public record Provider(
            String type,               // openai | anthropic
            String baseUrl,
            String model,
            String keyEnv,             // name of the environment variable holding the API key
            String reasoningEffort,    // null = don't send
            int maxOutputTokens,
            long minIntervalMs,        // pause between requests, to stay inside free-tier limits
            Price price,               // what we actually pay (0 on a free tier)
            Price paidPrice) {}        // list price on a paid plan, to report "what it would cost"

    /** USD per 1M tokens. */
    public record Price(double input, double output) {}

    public record Sources(Adzuna adzuna, Jooble jooble, Ats greenhouse, Ats lever, Ats ashby) {}

    public record Adzuna(boolean enabled, String appId, String appKey, List<String> queries,
                         int pages, int resultsPerPage, int maxDaysOld) {}

    public record Jooble(boolean enabled, String key, String baseUrl, List<String> queries, int pages) {}

    /** Public company career boards (Greenhouse, Lever, Ashby). */
    public record Ats(boolean enabled, List<String> companies) {}
}
