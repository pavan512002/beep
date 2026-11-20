package in.beep.jobs.source;

import com.fasterxml.jackson.databind.JsonNode;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.RawJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Lever public Postings API (no key). */
@Component
public class LeverSource implements JobSource {
    private static final Logger log = LoggerFactory.getLogger(LeverSource.class);

    private final BeepProperties.Ats cfg;
    private final Http http;

    public LeverSource(BeepProperties props, Http http) {
        this.cfg = props.sources().lever();
        this.http = http;
    }

    @Override public String name() { return "lever"; }

    @Override public boolean enabled() { return cfg.enabled() && !cfg.companies().isEmpty(); }

    @Override public FetchResult fetch() {
        FetchResult.Builder out = FetchResult.builder();
        for (String slug : cfg.companies()) {
            List<RawJob> got = new ArrayList<>();
            try {
                JsonNode jobs = http.getJson(URI.create("https://api.lever.co/v0/postings/" + slug + "?mode=json"));
                int kept = 0;
                for (JsonNode j : jobs) {
                    String title = j.path("text").asText();
                    String location = j.path("categories").path("location").asText(null);
                    if (!AtsFilter.isEntryLevel(title) || !AtsFilter.isIndia(location)) continue;
                    long created = j.path("createdAt").asLong(0);
                    got.add(new RawJob(name(), j.path("id").asText(), title, capitalize(slug), location,
                            j.path("descriptionPlain").asText(""), j.path("hostedUrl").asText(),
                            created > 0 ? Instant.ofEpochMilli(created) : null, null));
                    kept++;
                }
                log.info("lever {}: {} posts, {} entry-level in India", slug, jobs.size(), kept);
                out.ok(got);
            } catch (Exception e) {
                out.failed(slug, e);
                log.warn("lever {} failed: {}", slug, Http.categorize(e));
            }
        }
        return out.build();
    }

    static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
