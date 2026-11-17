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
import java.util.Map;

/** Jooble official REST API. Only runs when JOOBLE_KEY is set. https://jooble.org/api/about */
@Component
public class JoobleSource implements JobSource {
    private static final Logger log = LoggerFactory.getLogger(JoobleSource.class);

    private final BeepProperties.Jooble cfg;
    private final Http http;

    public JoobleSource(BeepProperties props, Http http) {
        this.cfg = props.sources().jooble();
        this.http = http;
    }

    @Override public String name() { return "jooble"; }

    @Override public boolean enabled() { return cfg.enabled() && AdzunaSource.notBlank(cfg.key()); }

    @Override public List<RawJob> fetch() {
        List<RawJob> out = new ArrayList<>();
        URI uri = URI.create(cfg.baseUrl() + cfg.key());
        for (String query : cfg.queries()) {
            for (int page = 1; page <= cfg.pages(); page++) {
                JsonNode jobs = http.postJson(uri,
                        Map.of("keywords", query, "location", "India", "page", String.valueOf(page))).path("jobs");
                for (JsonNode j : jobs) {
                    out.add(new RawJob(name(), j.path("id").asText(), j.path("title").asText(),
                            j.path("company").asText(null), j.path("location").asText(null),
                            j.path("snippet").asText(""), j.path("link").asText(),
                            AdzunaSource.parseInstant(j.path("updated").asText(null)),
                            j.path("salary").asText(null)));
                }
                log.info("jooble '{}' page {}: {} posts", query, page, jobs.size());
                if (jobs.isEmpty()) break;
                Http.sleep(300);
            }
        }
        return out;
    }
}
