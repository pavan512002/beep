package in.beep.jobs.tag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.Job;
import in.beep.jobs.model.JobTags;
import in.beep.jobs.normalize.CityAliases;
import in.beep.jobs.normalize.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Tags each post with one Claude call that must return JSON matching prompts/tag-schema.json
 * (structured output), so the role and other enums can never be off-list.
 */
@Component
public class ClaudeTagger {
    private static final Logger log = LoggerFactory.getLogger(ClaudeTagger.class);
    private static final int MAX_SKILLS = 5;

    private final BeepProperties.Tagging cfg;
    private final ObjectMapper mapper;
    private final String systemPrompt;
    private final JsonOutputFormat.Schema schema;
    private AnthropicClient client;   // created on first use, so runs without a key still work

    public ClaudeTagger(BeepProperties props, ObjectMapper mapper) throws Exception {
        this.cfg = props.tagging();
        this.mapper = mapper;
        this.systemPrompt = readResource("prompts/system-" + cfg.promptVersion() + ".txt");
        Map<String, Object> schemaMap = mapper.readValue(readResource("prompts/tag-schema.json"), new TypeReference<>() {});
        var sb = JsonOutputFormat.Schema.builder();
        schemaMap.forEach((k, v) -> sb.putAdditionalProperty(k, JsonValue.from(v)));
        this.schema = sb.build();
    }

    public boolean enabled() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return cfg.enabled() && key != null && !key.isBlank();
    }

    public String promptVersion() { return cfg.promptVersion(); }

    /** Cache key: changes when the post text, the model or the prompt version changes. */
    public String cacheKey(Job j, String model) {
        return Normalizer.sha1(cfg.promptVersion() + "|" + model + "|" + j.title + "|" + j.company
                + "|" + j.location + "|" + j.description);
    }

    /**
     * Tags every job that has no cached tags yet. Returns how many were newly tagged.
     * Jobs that fail keep tags == null and are retried on the next run.
     */
    public int tagAll(List<Job> jobs, String model, TagCache cache, CostTracker cost) throws InterruptedException {
        List<Job> todo = new ArrayList<>();
        for (Job j : jobs) {
            JobTags cached = cache.get(cacheKey(j, model));
            if (cached != null) apply(j, cached, model);
            else todo.add(j);
        }
        int cachedCount = jobs.size() - todo.size();
        if (todo.size() > cfg.maxPostsPerRun()) {
            log.warn("{} posts need tagging; capping at {} this run", todo.size(), cfg.maxPostsPerRun());
            todo = todo.subList(0, cfg.maxPostsPerRun());
        }
        log.info("Tagging {} new posts with {} ({} already cached)", todo.size(), model, cachedCount);
        if (todo.isEmpty()) return 0;

        ExecutorService pool = Executors.newFixedThreadPool(cfg.concurrency());
        List<Future<?>> futures = new ArrayList<>();
        for (Job j : todo) {
            futures.add(pool.submit(() -> {
                try {
                    JobTags tags = tagOne(j, model, cost);
                    if (tags != null) {
                        cache.put(cacheKey(j, model), tags);
                        apply(j, tags, model);
                    } else {
                        cost.failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    cost.failures.incrementAndGet();
                    log.warn("Tagging failed for '{}': {}", j.title, e.getMessage());
                }
            }));
        }
        int done = 0;
        for (Future<?> f : futures) {
            try { f.get(); } catch (ExecutionException ignored) { /* already logged */ }
            if (++done % 50 == 0) log.info("  tagged {}/{}", done, todo.size());
        }
        pool.shutdown();
        return (int) (todo.size() - cost.failures.get());
    }

    JobTags tagOne(Job j, String model, CostTracker cost) throws Exception {
        String user = "Title: " + j.title + "\nCompany: " + j.company + "\nLocation: " + j.location
                + "\nDescription: " + j.description;

        var builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .system(systemPrompt)
                .outputConfig(OutputConfig.builder()
                        .effort(OutputConfig.Effort.LOW)
                        .format(JsonOutputFormat.builder().schema(schema).build())
                        .build())
                .addUserMessage(user);
        // Classification needs no extended thinking. How to switch it off differs by model.
        if (model.contains("haiku")) builder.thinking(ThinkingConfigDisabled.builder().build());
        else if (model.equals("claude-sonnet-5-5")) builder.thinking(ThinkingConfigBetweenTools.builder().build());

        Message msg = client().messages().create(builder.build());
        cost.add(msg.usage());

        if (msg.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
            log.warn("Model declined to tag '{}'", j.title);
            return null;
        }
        String json = msg.content().stream()
                .flatMap(b -> b.text().stream())
                .map(TextBlock::text)
                .reduce("", String::concat);
        return clean(mapper.readValue(json, JobTags.class));
    }

    /** Canonical city names, no duplicates, at most 5 skills. */
    static JobTags clean(JobTags t) {
        List<String> cities = t.cities() == null ? List.of() : t.cities().stream()
                .map(CityAliases::canonicalize).filter(Objects::nonNull).distinct().toList();
        if (cities.isEmpty()) cities = List.of(CityAliases.UNSPECIFIED);
        if (cities.size() > 1 && cities.contains(CityAliases.UNSPECIFIED)) {
            cities = cities.stream().filter(c -> !c.equals(CityAliases.UNSPECIFIED)).toList();
        }
        List<String> skills = t.skills() == null ? List.of()
                : t.skills().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().limit(MAX_SKILLS).toList();
        return new JobTags(t.role(), cities, t.workMode(), t.jobType(), t.fresherFit(),
                t.expMinYears(), t.expMaxYears(), t.stipendMinInr(), t.stipendMaxInr(), skills, t.confidence());
    }

    private void apply(Job j, JobTags tags, String model) {
        j.tags = tags;
        j.tagModel = model;
        j.promptVersion = cfg.promptVersion();
        // Flag for review when the rule-based city parse and the LLM disagree.
        j.lowConfidence = "low".equals(tags.confidence())
                || (!j.ruleCities.isEmpty() && j.ruleCities.stream().noneMatch(tags.cities()::contains));
    }

    private synchronized AnthropicClient client() {
        if (client == null) client = AnthropicOkHttpClient.fromEnv();   // reads ANTHROPIC_API_KEY
        return client;
    }

    private static String readResource(String path) throws Exception {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
