package in.beep.jobs;

import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.dedup.Deduplicator;
import in.beep.jobs.model.Job;
import in.beep.jobs.model.RawJob;
import in.beep.jobs.normalize.Normalizer;
import in.beep.jobs.source.JobSource;
import in.beep.jobs.store.JsonStore;
import in.beep.jobs.tag.CostTracker;
import in.beep.jobs.tag.TagCache;
import in.beep.jobs.tag.Tagger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** The daily run: fetch → normalize → dedup → merge with yesterday → expire → tag → publish. */
@Component
public class PipelineRunner {
    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);
    private static final int HISTORY_DAYS = 30;

    private final BeepProperties props;
    private final List<JobSource> sources;
    private final Normalizer normalizer;
    private final Deduplicator deduplicator;
    private final Tagger tagger;
    private final JsonStore store;

    public PipelineRunner(BeepProperties props, List<JobSource> sources, Normalizer normalizer,
                          Deduplicator deduplicator, Tagger tagger, JsonStore store) {
        this.props = props;
        this.sources = sources;
        this.normalizer = normalizer;
        this.deduplicator = deduplicator;
        this.tagger = tagger;
        this.store = store;
    }

    public void run() throws Exception {
        Instant start = Instant.now();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("startedAt", start.toString());
        log.info("Repo root: {}", store.root());

        // 1. FETCH – each source on its own; one failure never stops the run
        List<RawJob> raw = new ArrayList<>();
        List<Map<String, Object>> sourceStats = new ArrayList<>();
        for (JobSource s : sources) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("name", s.name());
            if (!s.enabled()) {
                st.put("status", "disabled");
                log.info("Source {} disabled (switched off or no API key)", s.name());
            } else {
                long t0 = System.currentTimeMillis();
                try {
                    List<RawJob> got = s.fetch();
                    raw.addAll(got);
                    st.put("status", "ok");
                    st.put("fetched", got.size());
                } catch (Exception e) {
                    st.put("status", "error");
                    st.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    log.error("Source {} failed", s.name(), e);
                }
                st.put("ms", System.currentTimeMillis() - t0);
            }
            sourceStats.add(st);
        }
        log.info("Fetched {} raw posts", raw.size());

        // 2. NORMALIZE + 3. DEDUP
        List<Job> fresh = raw.stream().map(normalizer::normalize).filter(j -> !j.title.isBlank()).toList();
        Deduplicator.Result d = deduplicator.dedup(fresh);
        log.info("Dedup: {} raw → {} after exact URL → {} after fuzzy", d.raw(), d.afterExact(), d.afterFuzzy());

        // 4. MERGE with what was published yesterday (keeps firstSeenAt; survives a flaky source)
        Map<String, Job> previous = new HashMap<>();
        for (Job p : store.readJobs()) previous.put(p.id, p);
        Map<String, Job> merged = new LinkedHashMap<>();
        int newToday = 0;
        for (Job j : d.jobs()) {
            Job prev = previous.get(j.id);
            j.firstSeenAt = prev != null && prev.firstSeenAt != null ? prev.firstSeenAt : start;
            j.lastSeenAt = start;
            if (prev == null) newToday++;
            merged.put(j.id, j);
        }
        for (Job p : previous.values()) merged.putIfAbsent(p.id, p);   // not returned today: keep for now

        // 5. EXPIRE – too old, or no source has returned it for a few days
        Instant maxAge = start.minus(Duration.ofDays(props.maxAgeDays()));
        Instant stale = start.minus(Duration.ofDays(props.staleAfterDays()));
        List<Job> live = merged.values().stream()
                .filter(j -> j.postedAt == null || j.postedAt.isAfter(maxAge))
                .filter(j -> j.lastSeenAt == null || j.lastSeenAt.isAfter(stale))
                .toList();
        log.info("{} live posts after expiry ({} new today)", live.size(), newToday);

        // 6. TAG – only posts without cached tags
        TagCache cache = new TagCache(store.readTagCache());
        CostTracker cost = new CostTracker();
        int tagged = 0;
        if (tagger.enabled()) {
            tagged = tagger.tagAll(live, cache, cost);
            Set<String> liveKeys = new HashSet<>();
            for (Job j : live) liveKeys.add(tagger.cacheKey(j));
            cache.retainOnly(liveKeys);
            store.write(store.cacheFile(), cache.asMap(), true);
        } else {
            log.warn("Tagging disabled or no LLM API key set – publishing untagged posts");
        }

        // Keep yesterday's tags for any post that couldn't be (re)tagged this run, e.g. when a
        // provider is out of quota or the prompt version changed: old tags beat no tags.
        int keptOld = 0;
        for (Job j : live) {
            Job prev = previous.get(j.id);
            if (j.tags == null && prev != null && prev.tags != null) {
                j.tags = prev.tags;
                j.tagModel = prev.tagModel;
                j.promptVersion = prev.promptVersion;
                j.lowConfidence = prev.lowConfidence;
                keptOld++;
            }
        }
        if (keptOld > 0) log.info("Kept earlier tags for {} posts not tagged this run", keptOld);

        // 7. PUBLISH – newest first; posts the LLM says are not in India are left out
        List<Job> out = new ArrayList<>(live.stream()
                .filter(j -> j.tags == null || !"no".equals(j.tags.inIndia())).toList());
        int notInIndia = live.size() - out.size();
        if (notInIndia > 0) log.info("Left out {} posts that are not jobs in India", notInIndia);
        out.sort(Comparator.comparing((Job j) -> j.postedAt == null ? Instant.EPOCH : j.postedAt).reversed());
        store.write(store.jobsFile(), out, false);
        store.writeCsv(out);

        double runCost = cost.costUsd();
        double paidCost = cost.paidEquivalentUsd();
        run.put("finishedAt", Instant.now().toString());
        run.put("durationSec", Duration.between(start, Instant.now()).toSeconds());
        run.put("sources", sourceStats);
        run.put("dedup", Map.of("raw", d.raw(), "afterExactUrl", d.afterExact(), "afterFuzzy", d.afterFuzzy()));
        run.put("totalLive", out.size());
        run.put("newToday", newToday);
        run.put("notInIndiaRemoved", notInIndia);
        run.put("fresherFriendly", out.stream().filter(j -> j.tags != null && "yes".equals(j.tags.fresherFit())).count());
        run.put("keptEarlierTags", keptOld);
        run.put("untagged", out.stream().filter(j -> j.tags == null).count());
        run.put("lowConfidence", out.stream().filter(j -> j.lowConfidence).count());
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("providers", tagger.providerOrder());
        llm.put("promptVersion", tagger.promptVersion());
        llm.put("newlyTagged", tagged);
        llm.put("failedPosts", cost.failedPosts());
        llm.put("calls", cost.calls());
        llm.put("inputTokens", cost.inputTokens());
        llm.put("outputTokens", cost.outputTokens());
        llm.put("costUsd", round(runCost, 5));
        llm.put("costPer1000PostsUsd", tagged == 0 ? null : round(runCost / tagged * 1000, 4));
        // Same tokens priced at the providers' paid plans: the honest number if the free tier went away.
        llm.put("paidEquivalentUsd", round(paidCost, 5));
        llm.put("paidEquivalentPer1000PostsUsd", tagged == 0 ? null : round(paidCost / tagged * 1000, 4));
        llm.put("byModel", cost.breakdown());
        run.put("llm", llm);

        writeStats(run);
        log.info("Done in {}s: {} posts live, {} newly tagged, LLM cost ${}",
                run.get("durationSec"), out.size(), tagged, String.format("%.4f", runCost));
    }

    /** stats.json = latest run + short history (for the stats page) + eval results (kept across runs). */
    @SuppressWarnings("unchecked")
    private void writeStats(Map<String, Object> run) throws Exception {
        Map<String, Object> stats = store.readStats();
        List<Map<String, Object>> history = (List<Map<String, Object>>) stats.getOrDefault("history", new ArrayList<>());
        history = new ArrayList<>(history);
        history.add(0, Map.of(
                "at", run.get("startedAt"),
                "totalLive", run.get("totalLive"),
                "newToday", run.get("newToday"),
                "costUsd", ((Map<String, Object>) run.get("llm")).get("costUsd")));
        if (history.size() > HISTORY_DAYS) history = history.subList(0, HISTORY_DAYS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lastRun", run);
        out.put("history", history);
        if (stats.containsKey("eval")) out.put("eval", stats.get("eval"));
        store.write(store.statsFile(), out, true);
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
