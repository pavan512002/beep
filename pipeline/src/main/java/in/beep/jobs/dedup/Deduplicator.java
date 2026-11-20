package in.beep.jobs.dedup;

import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.Job;
import in.beep.jobs.normalize.Normalizer;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Two passes:
 *  1. exact  – same canonical URL
 *  2. fuzzy  – same company, near-identical title (Jaro-Winkler on word-sorted titles), overlapping city
 * Duplicates are merged, not dropped: the kept post lists every source it was seen on.
 */
@Component
public class Deduplicator {
    private static final JaroWinklerSimilarity JW = new JaroWinklerSimilarity();

    private final double threshold;

    public Deduplicator(BeepProperties props) {
        this.threshold = props.dedup().titleSimilarity();
    }

    public record Result(List<Job> jobs, int raw, int afterExact, int afterFuzzy) {}

    public Result dedup(List<Job> input) {
        // Pass 1: exact URL
        Map<String, Job> byUrl = new LinkedHashMap<>();
        List<Job> noUrl = new ArrayList<>();
        for (Job j : input) {
            if (j.url == null || j.url.isBlank()) { noUrl.add(j); continue; }
            byUrl.merge(j.url, j, Deduplicator::merge);
        }
        List<Job> afterExact = new ArrayList<>(byUrl.values());
        afterExact.addAll(noUrl);

        // Pass 2: fuzzy, only compared within the same company
        Map<String, List<Job>> byCompany = new LinkedHashMap<>();
        List<Job> result = new ArrayList<>();
        for (Job j : afterExact) {
            if (j.normCompany == null || j.normCompany.equals("unknown")) { result.add(j); continue; }
            List<Job> kept = byCompany.computeIfAbsent(j.normCompany, k -> new ArrayList<>());
            Job match = kept.stream().filter(k -> isSamePost(k, j)).findFirst().orElse(null);
            if (match != null) merge(match, j);
            else kept.add(j);
        }
        byCompany.values().forEach(result::addAll);

        assignIds(result);
        return new Result(result, input.size(), afterExact.size(), result.size());
    }

    /**
     * Every surviving post gets its own id. Two posts can share company + title + city without
     * being merged (unknown employer; no fuzzy evidence), so a collision falls back to an id that
     * also covers the link, then the source's own id; a plain index is the last resort.
     */
    static void assignIds(List<Job> jobs) {
        Map<String, List<Job>> byId = new LinkedHashMap<>();
        for (Job j : jobs) byId.computeIfAbsent(stableId(j), k -> new ArrayList<>()).add(j);
        Set<String> used = new HashSet<>();
        for (var e : byId.entrySet()) {
            if (e.getValue().size() == 1) {
                e.getValue().get(0).id = e.getKey();
                used.add(e.getKey());
            }
        }
        for (var e : byId.entrySet()) {
            if (e.getValue().size() == 1) continue;
            for (Job j : e.getValue()) {
                String id = discriminated(e.getKey(), j);
                for (int n = 2; used.contains(id); n++) id = discriminated(e.getKey() + "#" + n, j);
                j.id = id;
                used.add(id);
            }
        }
    }

    private static String discriminated(String base, Job j) {
        String disc = j.url != null && !j.url.isBlank() ? j.url : String.valueOf(j.sourceKey);
        return Normalizer.sha1(base + "|" + disc).substring(0, 16);
    }

    boolean isSamePost(Job a, Job b) {
        return JW.apply(a.normTitle, b.normTitle) >= threshold && citiesOverlap(a.ruleCities, b.ruleCities);
    }

    /** No parsed city on one side (location just "India") counts as compatible. */
    static boolean citiesOverlap(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return true;
        return a.stream().anyMatch(b::contains);
    }

    /** Fold `other` into `keep`. Returns `keep` so it can be used with Map.merge. */
    static Job merge(Job keep, Job other) {
        for (String s : other.sources) if (!keep.sources.contains(s)) keep.sources.add(s);
        if (other.description.length() > keep.description.length()) keep.description = other.description;
        if (other.postedAt != null && (keep.postedAt == null || other.postedAt.isBefore(keep.postedAt))) {
            keep.postedAt = other.postedAt;
        }
        if (keep.location.isBlank()) keep.location = other.location;
        for (String c : other.ruleCities) if (!keep.ruleCities.contains(c)) keep.ruleCities.add(c);
        if (keep.salaryText == null) keep.salaryText = other.salaryText;
        return keep;
    }

    /**
     * Same post gets the same id every day, which "New today" relies on. A post whose id changes
     * (e.g. a twin appears) is still matched to yesterday's copy by URL in the runner.
     */
    static String stableId(Job j) {
        String city = j.ruleCities.isEmpty() ? "" : j.ruleCities.get(0);
        return Normalizer.sha1(j.normCompany + "|" + j.normTitle + "|" + city).substring(0, 16);
    }
}
