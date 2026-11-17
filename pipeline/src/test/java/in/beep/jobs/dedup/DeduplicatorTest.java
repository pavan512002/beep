package in.beep.jobs.dedup;

import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.Job;
import in.beep.jobs.model.RawJob;
import in.beep.jobs.normalize.Normalizer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeduplicatorTest {
    private final BeepProperties props = new BeepProperties(".", "test", 30, 3, 2000,
            new BeepProperties.Dedup(0.92), null, null);
    private final Normalizer normalizer = new Normalizer(props);
    private final Deduplicator dedup = new Deduplicator(props);

    private Job job(String source, String title, String company, String location, String url, String posted) {
        return normalizer.normalize(new RawJob(source, "1", title, company, location, "desc", url,
                Instant.parse(posted), null));
    }

    @Test
    void sameUrlIsMergedAndSourcesCombined() {
        var r = dedup.dedup(List.of(
                job("adzuna", "Java Intern", "Acme", "Pune", "https://x.com/j/1?utm_source=a", "2026-11-10T00:00:00Z"),
                job("jooble", "Java Intern", "Acme", "Pune", "https://x.com/j/1/", "2026-11-12T00:00:00Z")));
        assertThat(r.afterExact()).isEqualTo(1);
        assertThat(r.jobs().get(0).sources).containsExactly("adzuna", "jooble");
        assertThat(r.jobs().get(0).postedAt).isEqualTo(Instant.parse("2026-11-10T00:00:00Z")); // earliest kept
    }

    @Test
    void samePostDifferentUrlsMergedByFuzzyMatch() {
        var r = dedup.dedup(List.of(
                job("adzuna", "Entry Level Associate", "ascendion", "Vadodara, Gujarat", "https://a.com/1", "2026-11-15T00:00:00Z"),
                job("adzuna", "Entry Level Associate", "Ascendion", "Vadodara, Gujarat", "https://a.com/2", "2026-11-07T00:00:00Z")));
        assertThat(r.afterExact()).isEqualTo(2);
        assertThat(r.afterFuzzy()).isEqualTo(1);
    }

    @Test
    void differentRolesAtSameCompanyAreKept() {
        var r = dedup.dedup(List.of(
                job("adzuna", "Data Analyst Intern", "Acme", "Pune", "https://a.com/1", "2026-11-15T00:00:00Z"),
                job("adzuna", "Marketing Intern", "Acme", "Pune", "https://a.com/2", "2026-11-15T00:00:00Z")));
        assertThat(r.afterFuzzy()).isEqualTo(2);
    }

    @Test
    void sameTitleInDifferentCitiesIsKept() {
        var r = dedup.dedup(List.of(
                job("adzuna", "Sales Trainee", "Acme", "Pune", "https://a.com/1", "2026-11-15T00:00:00Z"),
                job("adzuna", "Sales Trainee", "Acme", "Chennai", "https://a.com/2", "2026-11-15T00:00:00Z")));
        assertThat(r.afterFuzzy()).isEqualTo(2);
    }

    @Test
    void idIsStableAcrossRuns() {
        var a = dedup.dedup(List.of(job("adzuna", "Java Intern", "Acme", "Pune", "https://a.com/1", "2026-11-15T00:00:00Z")));
        var b = dedup.dedup(List.of(job("jooble", "Java Intern", "Acme Pvt Ltd", "Pune, Maharashtra", "https://b.com/9", "2026-11-16T00:00:00Z")));
        assertThat(a.jobs().get(0).id).isEqualTo(b.jobs().get(0).id);
    }
}
