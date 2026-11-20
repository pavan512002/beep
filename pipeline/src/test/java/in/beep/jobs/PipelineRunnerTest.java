package in.beep.jobs;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.dedup.Deduplicator;
import in.beep.jobs.model.Job;
import in.beep.jobs.model.RawJob;
import in.beep.jobs.normalize.Normalizer;
import in.beep.jobs.source.FetchResult;
import in.beep.jobs.source.JobSource;
import in.beep.jobs.store.JsonStore;
import in.beep.jobs.tag.Tagger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Multi-day runs of the whole pipeline against a temp repo, with fake sources and no network. */
class PipelineRunnerTest {
    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    static final Instant DAY1 = Instant.parse("2026-11-01T00:30:00Z");

    @TempDir Path root;
    BeepProperties props;
    FakeSource adzuna;

    /** A source whose next answer the test sets. */
    static class FakeSource implements JobSource {
        final String name;
        Supplier<FetchResult> next;
        FakeSource(String name) { this.name = name; }
        public String name() { return name; }
        public boolean enabled() { return true; }
        public FetchResult fetch() { return next.get(); }
    }

    @BeforeEach
    void setUp() {
        var tagging = new BeepProperties.Tagging(false, "v1", 8, 100, List.of(), Map.of());
        props = new BeepProperties(root.toString(), "test", 30, 3, 2000, new BeepProperties.Dedup(0.92), tagging, null);
        adzuna = new FakeSource("adzuna");
    }

    static RawJob raw(String key, String title, String company) {
        return new RawJob("adzuna", key, title, company, "Pune, Maharashtra", "desc " + key,
                "https://example.com/job/" + key, null, null);
    }

    static FetchResult ok(RawJob... jobs) {
        return new FetchResult(List.of(jobs), 1, 1, List.of());
    }

    void runOn(Instant day) throws Exception {
        var tagger = new Tagger(props, MAPPER, List.of());
        new PipelineRunner(props, List.of(adzuna), new Normalizer(props), new Deduplicator(props), tagger,
                new JsonStore(props, MAPPER), Clock.fixed(day, ZoneOffset.UTC)).run();
    }

    List<Job> jobs() throws Exception { return new JsonStore(props, MAPPER).readJobs(); }

    @SuppressWarnings("unchecked")
    Map<String, Object> lastRun() throws Exception {
        return (Map<String, Object>) new JsonStore(props, MAPPER).readStats().get("lastRun");
    }

    Set<String> titles() throws Exception {
        Set<String> t = new TreeSet<>();
        for (Job j : jobs()) t.add(j.title);
        return t;
    }

    static Instant day(int n) { return DAY1.plusSeconds(86_400L * (n - 1)); }

    @Test
    void outageKeepsTheCatalogAndRecoveryExpiresClosedPosts() throws Exception {
        adzuna.next = () -> ok(raw("a", "Data Analyst Intern", "Acme"), raw("b", "Java Trainee", "Globex"));
        runOn(day(1));
        assertEquals(Set.of("Data Analyst Intern", "Java Trainee"), titles());
        assertEquals("healthy", lastRun().get("status"));

        // Day 2: only page 1 of 2 worked and it no longer lists B. B must not start ageing out.
        adzuna.next = () -> new FetchResult(List.of(raw("a", "Data Analyst Intern", "Acme")), 2, 1,
                List.of(new FetchResult.Failure("'fresher' p2", "http-5xx")));
        runOn(day(2));
        assertEquals("degraded", lastRun().get("status"));

        // Days 3–5: total outage, with an exception whose text carries an API key.
        adzuna.next = () -> { throw new IllegalStateException("I/O error on GET https://api.adzuna.com/x?app_key=TOPSECRET99"); };
        for (int d = 3; d <= 5; d++) runOn(day(d));
        assertEquals(Set.of("Data Analyst Intern", "Java Trainee"), titles(), "nothing expires while the source is down");
        assertEquals("degraded", lastRun().get("status"));
        assertEquals(day(1).toString(), lastRun().get("lastSuccessfulCollectionAt"));
        String statsText = Files.readString(root.resolve("web/data/stats.json"));
        assertFalse(statsText.contains("TOPSECRET99"), "API key leaked into stats.json");
        assertFalse(statsText.contains("app_key"), "request URL leaked into stats.json");

        // Day 6: healthy again and only A is listed: B (last seen day 1) is now really gone.
        adzuna.next = () -> ok(raw("a", "Data Analyst Intern", "Acme"));
        runOn(day(6));
        assertEquals(Set.of("Data Analyst Intern"), titles());
        assertEquals("healthy", lastRun().get("status"));
    }

    @Test
    void healthyEmptyAnswerAfterABigCatalogIsSuspect() throws Exception {
        List<RawJob> many = new ArrayList<>();
        for (int i = 0; i < PipelineRunner.EMPTY_SUSPECT_MIN; i++) many.add(raw("k" + i, "Role " + i, "Co" + i));
        adzuna.next = () -> new FetchResult(many, 1, 1, List.of());
        runOn(day(1));
        adzuna.next = () -> ok();
        runOn(day(5));
        assertEquals(PipelineRunner.EMPTY_SUSPECT_MIN, jobs().size());
        assertEquals("degraded", lastRun().get("status"));
    }

    @Test
    void reIdedPostKeepsFirstSeenThroughUrlMatch() throws Exception {
        adzuna.next = () -> ok(raw("a", "Support Executive", null));
        runOn(day(1));
        String firstId = jobs().get(0).id;
        // Day 2: a twin (same unknown employer, title, city; different link) appears; both get new ids.
        adzuna.next = () -> ok(raw("a", "Support Executive", null), raw("b", "Support Executive", null));
        runOn(day(2));
        List<Job> js = jobs();
        assertEquals(2, js.size(), "both vacancies are kept");
        Job a = js.stream().filter(j -> j.url.endsWith("/a")).findFirst().orElseThrow();
        assertNotEquals(firstId, a.id);
        assertEquals(day(1), a.firstSeenAt, "same link: still the same post");
    }

    @Test
    void boardPostWithOnlyRemoteWaitsForIndiaConfirmation() throws Exception {
        var board = new FakeSource("greenhouse");
        board.next = () -> new FetchResult(List.of(
                new RawJob("greenhouse", "1", "Graduate Engineer", "Acme", "Remote", "d", "https://g.example/1", null, null),
                new RawJob("greenhouse", "2", "Graduate Analyst", "Acme", "Pune, India", "d", "https://g.example/2", null, null)),
                1, 1, List.of());
        var tagger = new Tagger(props, MAPPER, List.of());
        new PipelineRunner(props, List.of(board), new Normalizer(props), new Deduplicator(props), tagger,
                new JsonStore(props, MAPPER), Clock.fixed(day(1), ZoneOffset.UTC)).run();
        assertEquals(Set.of("Graduate Analyst"), titles(), "untagged 'Remote' board post is held back");
    }

    @Test
    void firstSeenSurvivesAcrossDays() throws Exception {
        adzuna.next = () -> ok(raw("a", "Data Analyst Intern", "Acme"));
        runOn(day(1));
        runOn(day(2));
        Job a = jobs().get(0);
        assertEquals(day(1), a.firstSeenAt);
        assertEquals(day(2), a.lastSeenAt);
    }
}
