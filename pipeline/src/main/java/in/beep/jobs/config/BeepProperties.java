package in.beep.jobs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/** All settings from application.yml under "beep". */
@ConfigurationProperties(prefix = "beep")
public record BeepProperties(
        String root,
        String userAgent,
        int maxAgeDays,
        int staleAfterDays,
        int descriptionMaxChars,
        Dedup dedup,
        Tagging tagging,
        Sources sources) {

    public record Dedup(double titleSimilarity) {}

    public record Tagging(
            boolean enabled,
            String model,
            String promptVersion,
            int concurrency,
            int maxPostsPerRun,
            Map<String, Price> prices) {}

    public record Price(double input, double output) {}

    public record Sources(Adzuna adzuna, Jooble jooble, Ats greenhouse, Ats lever, Ats ashby) {}

    public record Adzuna(boolean enabled, String appId, String appKey, List<String> queries,
                         int pages, int resultsPerPage, int maxDaysOld) {}

    public record Jooble(boolean enabled, String key, String baseUrl, List<String> queries, int pages) {}

    /** Public company career boards (Greenhouse, Lever, Ashby). */
    public record Ats(boolean enabled, List<String> companies) {}
}
