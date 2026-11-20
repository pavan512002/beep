package in.beep.jobs;

import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.dedup.Deduplicator;
import in.beep.jobs.model.Job;
import in.beep.jobs.model.RawJob;
import in.beep.jobs.normalize.Normalizer;
import in.beep.jobs.source.FetchResult;
import in.beep.jobs.source.Http;
import in.beep.jobs.source.JobSource;
import in.beep.jobs.store.JsonStore;
import in.beep.jobs.tag.CostTracker;
import in.beep.jobs.tag.TagCache;
import in.beep.jobs.tag.Tagger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** The daily run: fetch → normalize → dedup → merge with yesterday → expire → tag → publish. */
@Component
public class PipelineRunner {
    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);
    private static final int HISTORY_DAYS = 30;
    static final String CLAUDE_TAGS_DIR = "state/claude-tags";
    static final String PENDING_FILE = "state/pending-tags.json";
    private static final int CLAUDE_BATCH = 150;   // posts per routine run; more wait for the next run
    /** A source that returns nothing although it had at least this many live posts yesterday is suspect. */
    static final int EMPTY_SUSPECT_MIN = 20;

    private final BeepProperties props;
    private final List<JobSource> sources;
    private final Normalizer normalizer;
    private final Deduplicator deduplicator;
    private final Tagger tagger;
    private final JsonStore store;
    private final Clock clock;

    @Autowired
    public PipelineRunner(BeepProperties props, List<JobSource> sources, Normalizer normalizer,
                          Deduplicator deduplicator, Tagger tagger, JsonStore store) {
        this(props, sources, normalizer, deduplicator, tagger, store, Clock.systemUTC());
    }

    /** With a fixed clock, so tests can replay several days. */
    PipelineRunner(BeepProperties props, List<JobSource> sources, Normalizer normalizer,
                   Deduplicator deduplicator, Tagger tagger, JsonStore store, Clock clock) {
        this.clock = clock;
        this.props = props;
        this.sources = sources;
        this.normalizer = normalizer;
        this.deduplicator = deduplicator;
        this.tagger = tagger;
        this.store = store;
    }

    public void run() throws Exception {
        Instant start = clock.instant();
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("startedAt", start.toString());
        log.info("Repo root: {}", store.root());

        List<Job> published = store.readJobs();            // yesterday's catalog
        Map<String, Long> prevBySource = new HashMap<>();
        for (Job p : published) for (String src : p.sources) prevBySource.merge(src, 1L, Long::sum);
        Map<String, Object> oldStats = store.readStats();

        // 1. FETCH – each source on its own; one failure never stops the run
        List<RawJob> raw = new ArrayList<>();
        List<Map<String, Object>> sourceStats = new ArrayList<>();
        Set<String> healthy = new HashSet<>();               // sources whose every unit worked today
        for (JobSource s : sources) {
            Map<String, Object> st = new LinkedHashMap<>();
            st.put("name", s.name());
            if (!s.enabled()) {
                st.put("status", "disabled");
                log.info("Source {} disabled (switched off or no API key)", s.name());
            } else {
                long t0 = System.currentTimeMillis();
                try {
                    FetchResult got = s.fetch();
                    raw.addAll(got.jobs());
                    String status = sourceStatus(got, prevBySource.getOrDefault(s.name(), 0L));
                    if (status.equals("ok")) healthy.add(s.name());
                    st.put("status", status);
                    st.put("fetched", got.jobs().size());
                    st.put("unitsAttempted", got.unitsAttempted());
                    st.put("unitsOk", got.unitsOk());
                    if (!got.failures().isEmpty()) st.put("failures", got.failures());
                    if (!status.equals("ok")) log.warn("Source {} is {}: {}/{} units ok, {} posts",
                            s.name(), status, got.unitsOk(), got.unitsAttempted(), got.jobs().size());
                } catch (Exception e) {
                    // Category only: the exception text can hold a request URL with an API key in it.
                    st.put("status", "error");
                    st.put("failures", List.of(new FetchResult.Failure("all", Http.categorize(e))));
                    log.error("Source {} failed: {}", s.name(), Http.redact(String.valueOf(e)));
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
        Map<String, Job> previousByUrl = new HashMap<>();
        for (Job p : published) {
            previous.put(p.id, p);
            if (p.url != null) previousByUrl.put(p.url, p);
        }
        Map<String, Job> merged = new LinkedHashMap<>();
        Map<String, Job> prevOf = new HashMap<>();   // today's id -> yesterday's copy of the same post
        int newToday = 0;
        for (Job j : d.jobs()) {
            Job prev = previous.get(j.id);
            if (prev == null && j.url != null) prev = previousByUrl.get(j.url);   // same post, id changed
            if (prev != null) {
                previous.remove(prev.id);                                         // don't keep the old copy too
                prevOf.put(j.id, prev);
            }
            j.firstSeenAt = prev != null && prev.firstSeenAt != null ? prev.firstSeenAt : start;
            j.lastSeenAt = start;
            if (prev == null) newToday++;
            // Dedup guarantees unique ids; a repeat here would silently drop a vacancy, so stop the
            // run instead (the workflow then publishes nothing and yesterday's catalog stays live).
            if (merged.put(j.id, j) != null) throw new IllegalStateException("Duplicate post id " + j.id);
        }
        // Not returned today: keep for now, unless a post fetched today has the same link (an old copy).
        Set<String> todayUrls = new HashSet<>();
        for (Job j : d.jobs()) if (j.url != null) todayUrls.add(j.url);
        for (Job p : previous.values()) {
            if (p.url != null && todayUrls.contains(p.url)) continue;
            merged.putIfAbsent(p.id, p);
        }

        // 5. EXPIRE – too old, or no source has returned it for a few days. "Not returned" only
        //    counts when one of the post's sources worked fully today: during an outage a missing
        //    post says nothing about whether it closed, so it is kept (max age still applies).
        Instant maxAge = start.minus(Duration.ofDays(props.maxAgeDays()));
        Instant stale = start.minus(Duration.ofDays(props.staleAfterDays()));
        List<Job> live = merged.values().stream()
                .filter(j -> j.postedAt == null || j.postedAt.isAfter(maxAge))
                .filter(j -> j.lastSeenAt == null || j.lastSeenAt.isAfter(stale)
                        || j.sources.stream().noneMatch(healthy::contains))
                .toList();
        log.info("{} live posts after expiry ({} new today)", live.size(), newToday);

        // 6. TAG – only posts without cached tags
        TagCache cache = new TagCache(store.readTagCache());
        CostTracker cost = new CostTracker();
        int tagged = 0;
        Tagger.ImportResult claude = new Tagger.ImportResult(0, 0, 0);
        // Always run: cached tags and the rule checks apply even when no LLM key works today;
        // only posts that need a fresh LLM call are skipped then.
        if (props.tagging().enabled()) {
            // a) Tags pushed by the scheduled Claude Code routine (Pro plan) replace any others.
            claude = tagger.importClaudeTags(live, cache, store.root().resolve(CLAUDE_TAGS_DIR));
            // b) The free API models only tag brand-new posts; posts that already have (older)
            //    tags keep them until the Claude routine re-tags them, so free quota isn't spent twice.
            Set<String> hasOldTags = new HashSet<>();
            for (Job j : live) {
                Job prev = prevOf.getOrDefault(j.id, previous.get(j.id));
                if (prev == null || prev.tags == null) continue;
                // Same prompt but a different key means the post's text changed: its old tags may
                // be wrong now (e.g. "freshers welcome" became "3+ years"), so tag it again.
                boolean textChanged = prev.tagKey != null && tagger.promptVersion().equals(prev.promptVersion)
                        && !prev.tagKey.equals(tagger.cacheKey(j));
                if (!textChanged) hasOldTags.add(j.id);
            }
            if (!tagger.enabled()) log.warn("No LLM API key set – using cached tags only; new posts stay untagged");
            tagged = tagger.tagAll(live, cache, cost, hasOldTags);
            Set<String> liveKeys = new HashSet<>();
            for (Job j : live) liveKeys.add(tagger.cacheKey(j));
            cache.retainOnly(liveKeys);
            store.write(store.cacheFile(), cache.asMap(), true);
        } else {
            log.warn("Tagging switched off in application.yml – publishing without new tags");
        }

        // Keep yesterday's tags for any post that couldn't be (re)tagged this run, e.g. when a
        // provider is out of quota or the prompt version changed: old tags beat no tags.
        int keptOld = 0;
        for (Job j : live) {
            Job prev = prevOf.getOrDefault(j.id, previous.get(j.id));
            if (j.tags == null && prev != null && prev.tags != null) {
                j.tags = prev.tags;
                j.tagModel = prev.tagModel;
                j.promptVersion = prev.promptVersion;
                j.tagKey = prev.tagKey;
                j.lowConfidence = prev.lowConfidence;
                j.fixes = prev.fixes;
                keptOld++;
            }
            // Tags made from other text (post edited, prompt changed, or from before tagKey existed)
            // stay visible but are provisional until re-tagged.
            if (j.tags != null && !tagger.cacheKey(j).equals(j.tagKey)) j.lowConfidence = true;
        }
        if (keptOld > 0) log.info("Kept earlier tags for {} posts not tagged this run", keptOld);

        // 7. PUBLISH – newest first; posts the LLM says are not in India are left out, and posts
        //    with no location evidence of India wait until the LLM confirms they are.
        List<Job> out = new ArrayList<>(live.stream().filter(PipelineRunner::publishable).toList());
        int notInIndia = live.size() - out.size();
        if (notInIndia > 0) log.info("Left out {} posts that are not jobs in India", notInIndia);
        out.sort(Comparator.comparing((Job j) -> j.postedAt == null ? Instant.EPOCH : j.postedAt).reversed());
        store.write(store.jobsFile(), out, false);
        store.writeCsv(out);

        // Work list for the Claude Code routine: newest posts not yet tagged by it under this prompt.
        // Includes posts held back only for want of an India confirmation, so they can get one.
        List<Job> queueable = new ArrayList<>(out);
        for (Job j : live) if (!publishable(j) && j.tags == null) queueable.add(j);
        List<Map<String, Object>> pending = tagger.pendingForClaude(queueable, CLAUDE_BATCH);
        Map<String, Object> pendingFile = new LinkedHashMap<>();
        pendingFile.put("promptVersion", tagger.promptVersion());
        pendingFile.put("generatedAt", clock.instant().toString());
        pendingFile.put("count", pending.size());
        pendingFile.put("posts", pending);
        store.write(store.root().resolve(PENDING_FILE), pendingFile, true);
        log.info("{} posts queued for the Claude routine ({})", pending.size(), PENDING_FILE);

        double runCost = cost.costUsd();
        double paidCost = cost.paidEquivalentUsd();
        // Source health: a source "matters" if it is on today or had posts in yesterday's catalog.
        Map<String, Object> sourceHealth = new TreeMap<>(asMap(oldStats.get("sourceHealth")));
        boolean degraded = false;
        Instant lastCollected = start;
        for (Map<String, Object> st : sourceStats) {
            String name = (String) st.get("name");
            Map<String, Object> h = new LinkedHashMap<>(asMap(sourceHealth.get(name)));
            if (healthy.contains(name)) h.put("lastOkAt", start.toString());
            h.put("lastStatus", st.get("status"));
            sourceHealth.put(name, h);
            boolean matters = !"disabled".equals(st.get("status")) || prevBySource.containsKey(name);
            if (matters && !healthy.contains(name)) {
                degraded = true;
                Object ok = h.get("lastOkAt");
                Instant okAt = ok == null ? Instant.EPOCH : Instant.parse(ok.toString());
                if (okAt.isBefore(lastCollected)) lastCollected = okAt;
            }
        }

        run.put("finishedAt", clock.instant().toString());
        run.put("durationSec", Duration.between(start, clock.instant()).toSeconds());
        run.put("status", degraded ? "degraded" : "healthy");
        run.put("lastSuccessfulCollectionAt", lastCollected.equals(Instant.EPOCH) ? null : lastCollected.toString());
        run.put("sources", sourceStats);
        run.put("dedup", Map.of("raw", d.raw(), "afterExactUrl", d.afterExact(), "afterFuzzy", d.afterFuzzy()));
        run.put("totalLive", out.size());
        run.put("newToday", newToday);
        run.put("notInIndiaRemoved", notInIndia);
        run.put("fresherFriendly", out.stream().filter(j -> j.tags != null && "yes".equals(j.tags.fresherFit())).count());
        run.put("keptEarlierTags", keptOld);
        run.put("untagged", out.stream().filter(j -> j.tags == null).count());
        run.put("ruleFixedPosts", out.stream().filter(j -> j.fixes != null).count());
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
        llm.put("claudeImported", claude.imported());
        llm.put("claudeRejected", claude.rejected());
        llm.put("pendingForClaude", pending.size());
        // Which model's tags are live right now (the Claude routine's tags count as "claude-code:...").
        Map<String, Long> liveByModel = new TreeMap<>();
        for (Job j : out) liveByModel.merge(j.tagModel == null ? "untagged" : j.tagModel, 1L, Long::sum);
        llm.put("liveTagsByModel", liveByModel);
        run.put("llm", llm);

        writeStats(run, sourceHealth);
        log.info("Done in {}s: {} posts live, {} newly tagged, LLM cost ${}",
                run.get("durationSec"), out.size(), tagged, String.format("%.4f", runCost));
    }

    /** stats.json = latest run + short history (for the stats page) + eval results (kept across runs). */
    @SuppressWarnings("unchecked")
    private void writeStats(Map<String, Object> run, Map<String, Object> sourceHealth) throws Exception {
        Map<String, Object> stats = store.readStats();
        List<Map<String, Object>> history = (List<Map<String, Object>>) stats.getOrDefault("history", new ArrayList<>());
        history = new ArrayList<>(history);
        history.add(0, Map.of(
                "at", run.get("startedAt"),
                "totalLive", run.get("totalLive"),
                "newToday", run.get("newToday"),
                "status", run.get("status"),
                "costUsd", ((Map<String, Object>) run.get("llm")).get("costUsd")));
        if (history.size() > HISTORY_DAYS) history = history.subList(0, HISTORY_DAYS);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lastRun", run);
        out.put("history", history);
        out.put("sourceHealth", sourceHealth);
        if (stats.containsKey("eval")) out.put("eval", stats.get("eval"));
        store.write(store.statsFile(), out, true);
    }

    /**
     * ok – every unit worked. partial – some did. error – none did. empty – every unit worked but
     * returned nothing while the source had many live posts yesterday (likely a silent API change).
     * Only "ok" lets the source's missing posts expire.
     */
    static String sourceStatus(FetchResult r, long hadYesterday) {
        if (r.unitsAttempted() > 0 && r.unitsOk() == 0) return "error";
        if (r.unitsOk() < r.unitsAttempted()) return "partial";
        if (r.jobs().isEmpty() && hadYesterday >= EMPTY_SUSPECT_MIN) return "empty";
        return "ok";
    }

    static boolean publishable(Job j) {
        String inIndia = j.tags == null ? null : j.tags.inIndia();
        if (Boolean.TRUE.equals(j.indiaUnverified)) return "yes".equals(inIndia);
        return !"no".equals(inIndia);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }
}
