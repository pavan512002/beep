package in.beep.jobs.source;

import com.fasterxml.jackson.databind.JsonNode;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.RawJob;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** Greenhouse public Job Board API (no key). https://developers.greenhouse.io/job-board.html */
@Component
public class GreenhouseSource implements JobSource {
    private static final Logger log = LoggerFactory.getLogger(GreenhouseSource.class);

    private final BeepProperties.Ats cfg;
    private final Http http;

    public GreenhouseSource(BeepProperties props, Http http) {
        this.cfg = props.sources().greenhouse();
        this.http = http;
    }

    @Override public String name() { return "greenhouse"; }

    @Override public boolean enabled() { return cfg.enabled() && !cfg.companies().isEmpty(); }

    @Override public List<RawJob> fetch() {
        List<RawJob> out = new ArrayList<>();
        for (String slug : cfg.companies()) {
            try {
                String base = "https://boards-api.greenhouse.io/v1/boards/" + slug;
                String company = http.getJson(URI.create(base)).path("name").asText(slug);
                JsonNode jobs = http.getJson(URI.create(base + "/jobs?content=true")).path("jobs");
                int kept = 0;
                for (JsonNode j : jobs) {
                    String title = j.path("title").asText();
                    String location = j.path("location").path("name").asText(null);
                    if (!AtsFilter.isEntryLevel(title) || !AtsFilter.isIndia(location)) continue;
                    // `content` is HTML that is itself HTML-escaped, so unescape then strip tags.
                    String html = Jsoup.parse(j.path("content").asText("")).text();
                    out.add(new RawJob(name(), j.path("id").asText(), title, company, location,
                            Jsoup.parse(html).text(), j.path("absolute_url").asText(),
                            AdzunaSource.parseInstant(j.path("updated_at").asText(null)), null));
                    kept++;
                }
                log.info("greenhouse {}: {} posts, {} entry-level in India", slug, jobs.size(), kept);
            } catch (Exception e) {
                log.warn("greenhouse {} failed: {}", slug, e.getMessage());
            }
        }
        return out;
    }
}
