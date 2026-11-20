package in.beep.jobs.source;

import com.fasterxml.jackson.databind.JsonNode;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.RawJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Ashby public job board API (no key). */
@Component
public class AshbySource implements JobSource {
    private static final Logger log = LoggerFactory.getLogger(AshbySource.class);

    private final BeepProperties.Ats cfg;
    private final Http http;

    public AshbySource(BeepProperties props, Http http) {
        this.cfg = props.sources().ashby();
        this.http = http;
    }

    @Override public String name() { return "ashby"; }

    @Override public boolean enabled() { return cfg.enabled() && !cfg.companies().isEmpty(); }

    @Override public FetchResult fetch() {
        FetchResult.Builder out = FetchResult.builder();
        for (String slug : cfg.companies()) {
            List<RawJob> got = new ArrayList<>();
            try {
                JsonNode jobs = http.getJson(URI.create("https://api.ashbyhq.com/posting-api/job-board/" + slug)).path("jobs");
                int kept = 0;
                for (JsonNode j : jobs) {
                    String title = j.path("title").asText();
                    String location = j.path("location").asText(null);
                    if (!AtsFilter.isEntryLevel(title) || !AtsFilter.isIndia(location)) continue;
                    got.add(new RawJob(name(), j.path("id").asText(), title, LeverSource.capitalize(slug), location,
                            j.path("descriptionPlain").asText(""), j.path("jobUrl").asText(),
                            AdzunaSource.parseInstant(j.path("publishedAt").asText(null)), null));
                    kept++;
                }
                log.info("ashby {}: {} posts, {} entry-level in India", slug, jobs.size(), kept);
                out.ok(got);
            } catch (Exception e) {
                out.failed(slug, e);
                log.warn("ashby {} failed: {}", slug, Http.categorize(e));
            }
        }
        return out.build();
    }
}
