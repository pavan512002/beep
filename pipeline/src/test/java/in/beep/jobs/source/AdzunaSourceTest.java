package in.beep.jobs.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import in.beep.jobs.config.BeepProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class AdzunaSourceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Answers by page number; never touches the network. */
    static class StubHttp extends Http {
        final BiFunction<String, Integer, JsonNode> answer;   // (query, page) -> body or throws
        StubHttp(BiFunction<String, Integer, JsonNode> answer) { super(MAPPER); this.answer = answer; }

        @Override public JsonNode getJson(URI uri) {
            String path = uri.getPath();
            int page = Integer.parseInt(path.substring(path.lastIndexOf('/') + 1));
            String what = uri.getQuery().replaceAll(".*what=([^&]*).*", "$1");
            return answer.apply(what, page);
        }
    }

    static JsonNode page(int n, String prefix) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode results = root.putArray("results");
        for (int i = 0; i < n; i++) {
            ObjectNode r = results.addObject();
            r.put("id", prefix + i);
            r.put("title", "Trainee " + prefix + i);
            r.putObject("company").put("display_name", "Acme");
            r.putObject("location").put("display_name", "Pune, Maharashtra");
            r.put("redirect_url", "https://adzuna.example/" + prefix + i);
        }
        return root;
    }

    private static AdzunaSource source(StubHttp http, String... queries) {
        var adz = new BeepProperties.Adzuna(true, "id", "key", List.of(queries), 3, 2, 30);
        var props = new BeepProperties(".", "test", 30, 3, 2000, new BeepProperties.Dedup(0.92), null,
                new BeepProperties.Sources(adz, null, null, null, null));
        return new AdzunaSource(props, http);
    }

    @Test
    void failedLaterPageKeepsEarlierPages() {
        var http = new StubHttp((q, p) -> {
            if (p == 2) throw new ResourceAccessException("I/O error ?app_key=key", new SocketTimeoutException());
            return page(2, q + p);
        });
        FetchResult r = source(http, "fresher").fetch();
        assertEquals(2, r.jobs().size(), "page 1 survives page 2 failing");
        assertEquals(2, r.unitsAttempted());
        assertEquals(1, r.unitsOk());
        assertEquals("timeout", r.failures().get(0).category());
        assertEquals("'fresher' p2", r.failures().get(0).unit());
    }

    @Test
    void oneQueryFailingDoesNotStopTheNext() {
        var http = new StubHttp((q, p) -> {
            if (q.equals("intern")) throw new IllegalStateException("boom");
            return page(1, q + p);   // 1 < resultsPerPage: last page
        });
        FetchResult r = source(http, "intern", "fresher").fetch();
        assertEquals(1, r.jobs().size());
        assertEquals(2, r.unitsAttempted());
        assertEquals(1, r.unitsOk());
    }
}
