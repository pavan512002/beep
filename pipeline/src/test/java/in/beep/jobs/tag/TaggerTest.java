package in.beep.jobs.tag;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.Job;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class TaggerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final BeepProperties.Provider FREE = new BeepProperties.Provider(
            "openai", "http://x", "m", "NONE", null, 1000, 0,
            new BeepProperties.Price(0, 0), new BeepProperties.Price(0.15, 0.60));

    /** Fake provider: answers with a function of the user message, or throws. */
    static class FakeClient implements LlmClient {
        final String name;
        final Function<String, Object> answer;   // returns String json or throws via LlmException wrapper
        int calls;

        FakeClient(String name, Function<String, Object> answer) { this.name = name; this.answer = answer; }

        public String name() { return name; }
        public BeepProperties.Provider config() { return FREE; }
        public boolean hasKey() { return true; }

        public Result complete(String system, String user, Map<String, Object> schema) throws LlmException {
            calls++;
            Object a = answer.apply(user);
            if (a instanceof LlmException e) throw e;
            return new Result((String) a, "stop", 1000, 200);
        }
    }

    private static Tagger tagger(int batchSize, LlmClient... clients) throws Exception {
        var tagging = new BeepProperties.Tagging(true, "v1", batchSize, 100, List.of(), Map.of());
        var props = new BeepProperties(".", "test", 30, 3, 2000, new BeepProperties.Dedup(0.92), tagging, null);
        return new Tagger(props, MAPPER, List.of(clients));
    }

    private static Job job(String title, String location, String... ruleCities) {
        Job j = new Job();
        j.title = title;
        j.company = "Acme";
        j.location = location;
        j.description = "desc";
        j.ruleCities = List.of(ruleCities);
        return j;
    }

    private static String item(int i, String role, String... cities) {
        String c = String.join("\",\"", cities);
        return """
               {"i":%d,"role":"%s","cities":["%s"],"workMode":"unknown","jobType":"internship","fresherFit":"yes",
                "expMinYears":null,"expMaxYears":null,"stipendMinInr":10000,"stipendMaxInr":10000,
                "skills":["SQL"," SQL ","Excel"],"confidence":"high"}""".formatted(i, role, c);
    }

    @Test
    void batchIsMatchedByNumber_invalidRoleRejected_citiesCanonicalised() throws Exception {
        // Answers out of order; post 2 gets an off-list role.
        var fake = new FakeClient("groq", u -> "{\"results\":[" + item(3, "Design", "Gurgaon") + ","
                + item(1, "Data & Analytics", "Bangalore") + "," + item(2, "Astronaut", "Pune") + "]}");
        Tagger t = tagger(10, fake);
        List<Job> jobs = List.of(job("Data Intern", "Bangalore", "Bengaluru"), job("X", "Pune"), job("UI Intern", "Gurgaon"));
        TagCache cache = new TagCache(Map.of());
        CostTracker cost = new CostTracker();

        assertEquals(2, t.tagAll(jobs, cache, cost));
        assertEquals(1, fake.calls);
        assertEquals("Data & Analytics", jobs.get(0).tags.role());
        assertEquals(List.of("Bengaluru"), jobs.get(0).tags.cities());
        assertEquals(List.of("SQL", "Excel"), jobs.get(0).tags.skills());
        assertFalse(jobs.get(0).lowConfidence);
        assertNull(jobs.get(1).tags);                       // rejected, retried next run
        assertEquals(List.of("Gurugram"), jobs.get(2).tags.cities());
        assertEquals("groq:m", jobs.get(2).tagModel);
        assertEquals(1, cost.failedPosts());
        assertEquals(0.0, cost.costUsd());
        assertTrue(cost.paidEquivalentUsd() > 0);
    }

    @Test
    void cachedPostsAreNotSentAgain() throws Exception {
        var fake = new FakeClient("groq", u -> "{\"results\":[" + item(1, "Marketing", "Mumbai") + "]}");
        Tagger t = tagger(10, fake);
        TagCache cache = new TagCache(Map.of());
        t.tagAll(List.of(job("SEO Intern", "Mumbai")), cache, new CostTracker());

        Job again = job("SEO Intern", "Mumbai");
        assertEquals(0, t.tagAll(List.of(again), cache, new CostTracker()));
        assertEquals(1, fake.calls);
        assertEquals("Marketing", again.tags.role());
    }

    @Test
    void dailyLimitSwitchesToNextProvider() throws Exception {
        var groq = new FakeClient("groq", u -> new LlmException(LlmException.Kind.SWITCH_PROVIDER, "daily limit", 0));
        var gemini = new FakeClient("gemini", u -> "{\"results\":[" + item(1, "Operations", "Chennai") + "," + item(2, "Operations", "Chennai") + "]}");
        Tagger t = tagger(2, groq, gemini);
        List<Job> jobs = List.of(job("Ops 1", "Chennai"), job("Ops 2", "Chennai"), job("Ops 3", "Chennai"), job("Ops 4", "Chennai"));

        assertEquals(4, t.tagAll(jobs, new TagCache(Map.of()), new CostTracker()));
        assertEquals(1, groq.calls);        // not called again once out of quota
        assertEquals(2, gemini.calls);
        assertEquals("gemini:m", jobs.get(3).tagModel);
    }

    @Test
    void noProviderLeftLeavesPostsUntagged() throws Exception {
        var groq = new FakeClient("groq", u -> new LlmException(LlmException.Kind.SWITCH_PROVIDER, "key rejected", 0));
        Tagger t = tagger(2, groq);
        CostTracker cost = new CostTracker();
        List<Job> jobs = List.of(job("A", "Pune"), job("B", "Pune"), job("C", "Pune"));

        assertEquals(0, t.tagAll(jobs, new TagCache(Map.of()), cost));
        assertEquals(3, cost.failedPosts());
        assertTrue(jobs.stream().allMatch(j -> j.tags == null));
    }

    @Test
    void batchSchemaWrapsItemsAndRequiresNumber() throws Exception {
        var fake = new FakeClient("groq", u -> "{\"results\":[]}");
        Tagger t = tagger(10, fake);
        String msg = t.userMessage(List.of(job("A", "Pune"), job("B", "Delhi")));
        assertTrue(msg.contains("Post 1\nTitle: A"));
        assertTrue(msg.contains("Post 2\nTitle: B"));
    }

    @Test
    void http429DailyVsPerMinute() {
        assertEquals(LlmException.Kind.SWITCH_PROVIDER, OpenAiCompatClient.classify(429,
                "Rate limit reached for model on tokens per day (TPD): Limit 200000", null).kind);
        assertEquals(LlmException.Kind.RETRY, OpenAiCompatClient.classify(429,
                "Rate limit reached on tokens per minute (TPM): Limit 8000", null).kind);
        assertEquals(LlmException.Kind.SWITCH_PROVIDER, OpenAiCompatClient.classify(401, "invalid key", null).kind);
        assertEquals(LlmException.Kind.SKIP_BATCH, OpenAiCompatClient.classify(413, "too large", null).kind);
    }
}
