package in.beep.jobs.source;

import com.fasterxml.jackson.databind.JsonNode;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.RawJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Adzuna official API, India market. https://developer.adzuna.com */
@Component
public class AdzunaSource implements JobSource {
    private static final Logger log = LoggerFactory.getLogger(AdzunaSource.class);

    private final BeepProperties.Adzuna cfg;
    private final Http http;

    public AdzunaSource(BeepProperties props, Http http) {
        this.cfg = props.sources().adzuna();
        this.http = http;
    }

    @Override public String name() { return "adzuna"; }

    @Override public boolean enabled() {
        return cfg.enabled() && notBlank(cfg.appId()) && notBlank(cfg.appKey());
    }

    @Override public FetchResult fetch() {
        FetchResult.Builder out = FetchResult.builder();
        for (String query : cfg.queries()) {
            for (int page = 1; page <= cfg.pages(); page++) {
                JsonNode results;
                try {
                    results = http.getJson(uri(query, page)).path("results");
                } catch (Exception e) {
                    // Keep the pages already fetched; stop this query, move on to the next one.
                    out.failed("'" + query + "' p" + page, e);
                    log.warn("adzuna '{}' page {} failed: {}", query, page, Http.categorize(e));
                    break;
                }
                List<RawJob> got = new ArrayList<>();
                for (JsonNode r : results) got.add(toRaw(r));
                out.ok(got);
                log.info("adzuna '{}' page {}: {} posts", query, page, results.size());
                if (results.size() < cfg.resultsPerPage()) break;   // last page
                Http.sleep(300);                                    // be polite
            }
        }
        return out.build();
    }

    private java.net.URI uri(String query, int page) {
        // app_id/app_key travel in the query string: never log this URI (Http.redact if you must).
        return UriComponentsBuilder
                .fromUriString("https://api.adzuna.com/v1/api/jobs/in/search/" + page)
                .queryParam("app_id", cfg.appId())
                .queryParam("app_key", cfg.appKey())
                .queryParam("what", query)
                .queryParam("results_per_page", cfg.resultsPerPage())
                .queryParam("max_days_old", cfg.maxDaysOld())
                .queryParam("sort_by", "date")
                .encode().build().toUri();
    }

    private RawJob toRaw(JsonNode r) {
        String salary = null;
        if (r.hasNonNull("salary_min") && !r.path("salary_is_predicted").asText("0").equals("1")) {
            long max = r.path("salary_max").asLong(0);   // often absent: don't print "- 0"
            salary = "INR " + r.get("salary_min").asLong() + (max > 0 ? " - " + max : "") + " per year";
        }
        return new RawJob(
                name(),
                r.path("id").asText(),
                r.path("title").asText(),
                r.path("company").path("display_name").asText(null),
                r.path("location").path("display_name").asText(null),
                r.path("description").asText(""),
                r.path("redirect_url").asText(),
                parseInstant(r.path("created").asText(null)),
                salary);
    }

    static Instant parseInstant(String s) {
        try { return s == null ? null : Instant.parse(s); } catch (Exception e) { return null; }
    }

    static boolean notBlank(String s) { return s != null && !s.isBlank(); }
}
