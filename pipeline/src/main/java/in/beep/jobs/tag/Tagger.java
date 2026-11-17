package in.beep.jobs.tag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.Job;
import in.beep.jobs.model.JobTags;
import in.beep.jobs.normalize.CityAliases;
import in.beep.jobs.normalize.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Tags posts with an LLM, several posts per request.
 *
 * Built for free tiers: the long instructions are sent once per batch instead of once per post,
 * requests are spaced out, and when a provider runs out of daily quota the next one in
 * beep.tagging.use takes over. Posts that still fail stay untagged and are retried next run.
 */
@Component
public class Tagger {
    private static final Logger log = LoggerFactory.getLogger(Tagger.class);
    private static final int MAX_SKILLS = 6;
    private static final int MAX_IMPLIED = 4;
    private static final int MAX_RETRIES = 4;
    private static final long MAX_WAIT_MS = 120_000;

    private final BeepProperties.Tagging cfg;
    private final ObjectMapper mapper;
    private final List<LlmClient> clients;
    private final String systemPrompt;
    private final Map<String, Object> batchSchema;
    private final Map<String, Set<String>> allowed;   // enum values per field, read from the schema
    private final Map<String, String> skillList;       // lower-case name → name, from prompts/skills.json

    @Autowired
    public Tagger(BeepProperties props, ObjectMapper mapper) throws Exception {
        this(props, mapper, createClients(props.tagging(), mapper));
    }

    Tagger(BeepProperties props, ObjectMapper mapper, List<LlmClient> clients) throws Exception {
        this.cfg = props.tagging();
        this.mapper = mapper;
        this.clients = clients;
        this.systemPrompt = readResource("prompts/system-" + cfg.promptVersion() + ".txt");
        Map<String, Object> itemSchema = mapper.readValue(readResource("prompts/tag-schema.json"), new TypeReference<>() {});
        this.allowed = enumsOf(itemSchema);
        List<String> names = mapper.readValue(readResource("prompts/skills.json"), new TypeReference<>() {});
        this.skillList = new HashMap<>();
        names.forEach(n -> skillList.put(n.toLowerCase(Locale.ROOT), n));
        this.batchSchema = wrapForBatch(itemSchema);
    }

    private static List<LlmClient> createClients(BeepProperties.Tagging cfg, ObjectMapper mapper) {
        List<LlmClient> list = new ArrayList<>();
        for (String name : cfg.use()) {
            BeepProperties.Provider p = cfg.providers().get(name);
            if (p == null) throw new IllegalArgumentException("beep.tagging.use lists '" + name + "' but no provider has that name");
            list.add("anthropic".equals(p.type()) ? new AnthropicLlmClient(name, p) : new OpenAiCompatClient(name, p, mapper));
        }
        return list;
    }

    public boolean enabled() {
        return cfg.enabled() && clients.stream().anyMatch(LlmClient::hasKey);
    }

    public String promptVersion() { return cfg.promptVersion(); }

    public List<String> providerOrder() { return clients.stream().map(LlmClient::label).toList(); }

    /** Changes when the post text or the prompt version changes. */
    public String cacheKey(Job j) {
        return Normalizer.sha1(cfg.promptVersion() + "|" + j.title + "|" + j.company
                + "|" + j.location + "|" + j.description);
    }

    /** Tags every job not in the cache. Returns how many were newly tagged. */
    public int tagAll(List<Job> jobs, TagCache cache, CostTracker cost) {
        List<Job> todo = new ArrayList<>();
        for (Job j : jobs) {
            TagCache.Entry hit = cache.get(cacheKey(j));
            if (hit != null) apply(j, hit.tags(), hit.model());
            else todo.add(j);
        }
        int cachedCount = jobs.size() - todo.size();
        if (todo.size() > cfg.maxPostsPerRun()) {
            log.warn("{} posts need tagging; capping at {} this run", todo.size(), cfg.maxPostsPerRun());
            todo = todo.subList(0, cfg.maxPostsPerRun());
        }
        Deque<LlmClient> queue = new ArrayDeque<>();
        for (LlmClient c : clients) {
            if (c.hasKey()) queue.add(c);
            else log.info("LLM provider {} skipped: {} not set", c.name(), c.config().keyEnv());
        }
        log.info("Tagging {} new posts in batches of {} ({} already cached), providers {}",
                todo.size(), cfg.batchSize(), cachedCount, queue.stream().map(LlmClient::label).toList());
        if (todo.isEmpty()) return 0;

        LlmClient current = queue.poll();
        long lastCallAt = 0;
        int tagged = 0;
        for (int from = 0; from < todo.size(); from += cfg.batchSize()) {
            List<Job> batch = todo.subList(from, Math.min(from + cfg.batchSize(), todo.size()));
            int attempts = 0;
            while (true) {
                if (current == null) {
                    int left = todo.size() - from;
                    log.warn("No LLM provider left for today; {} posts stay untagged until the next run", left);
                    cost.failed(left);
                    return tagged;
                }
                sleep(current.config().minIntervalMs() - (System.currentTimeMillis() - lastCallAt));
                lastCallAt = System.currentTimeMillis();
                try {
                    LlmClient.Result r = current.complete(systemPrompt, userMessage(batch), batchSchema);
                    cost.add(current, r.inputTokens(), r.outputTokens());
                    int ok = applyResults(batch, r, current.label(), cache);
                    if (ok < batch.size()) cost.failed(batch.size() - ok);
                    tagged += ok;
                    break;
                } catch (LlmException e) {
                    if (e.kind == LlmException.Kind.RETRY && ++attempts <= MAX_RETRIES) {
                        long wait = Math.min(e.retryAfterMs, MAX_WAIT_MS);
                        log.info("{}: {} – waiting {}s", current.name(), firstLine(e.getMessage()), wait / 1000);
                        sleep(wait);
                    } else if (e.kind == LlmException.Kind.SWITCH_PROVIDER) {
                        log.warn("{}: {} – switching provider", current.name(), firstLine(e.getMessage()));
                        current = queue.poll();
                        attempts = 0;
                    } else {
                        log.warn("{}: batch of {} skipped: {}", current.name(), batch.size(), firstLine(e.getMessage()));
                        cost.failed(batch.size());
                        break;
                    }
                }
            }
            log.info("  tagged {}/{}", tagged, todo.size());
        }
        return tagged;
    }

    /** Posts numbered from 1; the model returns "i" so answers can't get mixed up. */
    String userMessage(List<Job> batch) {
        StringBuilder b = new StringBuilder("Tag each of these ").append(batch.size())
                .append(" job posts. Return exactly one result per post in \"results\", with \"i\" set to the post's number.\n");
        for (int i = 0; i < batch.size(); i++) {
            Job j = batch.get(i);
            b.append("\nPost ").append(i + 1)
             .append("\nTitle: ").append(j.title)
             .append("\nCompany: ").append(j.company)
             .append("\nLocation: ").append(j.location)
             .append("\nDescription: ").append(j.description == null ? "" : j.description)
             .append('\n');
        }
        return b.toString();
    }

    /** Applies each valid result to its post. Returns how many posts got tags. */
    int applyResults(List<Job> batch, LlmClient.Result r, String model, TagCache cache) {
        if (r.json() == null) {
            log.warn("Model returned no answer (finish reason: {})", r.finishReason());
            return 0;
        }
        if ("length".equals(r.finishReason())) {
            log.warn("Answer was cut off at the token limit; raise max-output-tokens or lower batch-size");
            return 0;
        }
        JsonNode results;
        try {
            results = mapper.readTree(r.json()).path("results");
        } catch (Exception e) {
            log.warn("Answer is not valid JSON: {}", e.getMessage());
            return 0;
        }
        boolean[] done = new boolean[batch.size()];
        int ok = 0;
        for (JsonNode item : results) {
            int i = item.path("i").asInt(-1) - 1;
            if (i < 0 || i >= batch.size() || done[i] || !(item instanceof ObjectNode obj)) continue;
            obj.remove("i");
            try {
                JobTags t = clean(mapper.treeToValue(obj, JobTags.class), skillList);
                String problem = invalid(t);
                if (problem != null) {
                    log.warn("Rejected tags for '{}': {}", batch.get(i).title, problem);
                    continue;
                }
                Job job = batch.get(i);
                cache.put(cacheKey(job), new TagCache.Entry(t, model));
                apply(job, t, model);
                done[i] = true;
                ok++;
            } catch (Exception e) {
                log.warn("Could not read tags for '{}': {}", batch.get(i).title, e.getMessage());
            }
        }
        return ok;
    }

    /** Schemas aren't enforced the same way by every provider, so check the enum fields ourselves. */
    String invalid(JobTags t) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("inIndia", t.inIndia());
        values.put("role", t.role());
        values.put("workMode", t.workMode());
        values.put("jobType", t.jobType());
        values.put("fresherFit", t.fresherFit());
        values.put("confidence", t.confidence());
        for (var e : values.entrySet()) {
            Set<String> ok = allowed.get(e.getKey());
            if (ok != null && !ok.contains(e.getValue())) return e.getKey() + " = " + e.getValue();
        }
        return null;
    }

    /** Canonical city names; skills only from the fixed list, no duplicates, implied ≠ stated. */
    static JobTags clean(JobTags t, Map<String, String> skillList) {
        List<String> cities = t.cities() == null ? List.of() : t.cities().stream()
                .map(CityAliases::canonicalize).filter(Objects::nonNull).distinct().toList();
        if (cities.isEmpty()) cities = List.of(CityAliases.UNSPECIFIED);
        if (cities.size() > 1 && cities.contains(CityAliases.UNSPECIFIED)) {
            cities = cities.stream().filter(c -> !c.equals(CityAliases.UNSPECIFIED)).toList();
        }
        List<String> skills = onlyKnown(t.skills(), skillList, Set.of(), MAX_SKILLS);
        List<String> implied = onlyKnown(t.impliedSkills(), skillList, new HashSet<>(skills), MAX_IMPLIED);
        return new JobTags(t.inIndia(), t.role(), cities, t.workMode(), t.jobType(), t.fresherFit(),
                t.expMinYears(), t.expMaxYears(), t.stipendMinInr(), t.stipendMaxInr(), skills, implied, t.confidence());
    }

    /** Keeps names on the fixed list (matched ignoring case, returned in the list's spelling). */
    private static List<String> onlyKnown(List<String> in, Map<String, String> known, Set<String> exclude, int max) {
        if (in == null) return List.of();
        return in.stream().filter(Objects::nonNull)
                .map(s -> known.isEmpty() ? s.trim() : known.get(s.trim().toLowerCase(Locale.ROOT)))
                .filter(Objects::nonNull).filter(s -> !exclude.contains(s))
                .distinct().limit(max).toList();
    }

    private void apply(Job j, JobTags tags, String model) {
        TagRules.Result checked = TagRules.check(j, tags);
        j.tags = checked.tags();
        j.fixes = checked.fixes().isEmpty() ? null : checked.fixes();
        j.tagModel = model;
        j.promptVersion = cfg.promptVersion();
        // Flag for review when the model was unsure or a rule had to correct it.
        j.lowConfidence = "low".equals(tags.confidence()) || j.fixes != null;
    }

    /** {"results": [ {"i": 1, ...one post's tags...}, ... ]} */
    @SuppressWarnings("unchecked")
    static Map<String, Object> wrapForBatch(Map<String, Object> itemSchema) {
        Map<String, Object> item = new LinkedHashMap<>(itemSchema);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("i", Map.of("type", "integer", "description", "The post's number."));
        props.putAll((Map<String, Object>) itemSchema.get("properties"));
        item.put("properties", props);
        List<Object> required = new ArrayList<>();
        required.add("i");
        required.addAll((List<Object>) itemSchema.get("required"));
        item.put("required", required);

        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("type", "object");
        batch.put("additionalProperties", false);
        batch.put("required", List.of("results"));
        batch.put("properties", Map.of("results", Map.of("type", "array", "items", item)));
        return batch;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> enumsOf(Map<String, Object> schema) {
        Map<String, Set<String>> out = new HashMap<>();
        ((Map<String, Object>) schema.get("properties")).forEach((field, def) -> {
            Object e = ((Map<String, Object>) def).get("enum");
            if (e instanceof List<?> l) out.put(field, new HashSet<>((List<String>) l));
        });
        return out;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readResource(String path) throws Exception {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
