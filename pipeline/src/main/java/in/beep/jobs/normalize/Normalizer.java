package in.beep.jobs.normalize;

import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.Job;
import in.beep.jobs.model.RawJob;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Turns a source-specific RawJob into a clean Job with one shared shape. */
@Component
public class Normalizer {
    private static final Pattern COMPANY_SUFFIX = Pattern.compile(
            "\\b(private limited|pvt\\.? ltd\\.?|pvt|ltd\\.?|limited|llp|inc\\.?|incorporated|corporation|corp\\.?|technologies|technology|solutions|services|india)\\b");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Set<String> TRACKING_PARAMS = Set.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "ref", "source", "gh_src", "lever-source");

    private static final Set<String> ATS_SOURCES = Set.of("greenhouse", "lever", "ashby");

    private final int maxChars;

    public Normalizer(BeepProperties props) {
        this.maxChars = props.descriptionMaxChars();
    }

    public Job normalize(RawJob raw) {
        Job j = new Job();
        j.title = cleanText(raw.title());
        j.company = raw.company() == null || raw.company().isBlank() ? "Unknown" : cleanText(raw.company());
        j.location = raw.location() == null ? "" : cleanText(raw.location());
        String desc = cleanText(raw.description());
        j.description = desc.length() > maxChars ? desc.substring(0, maxChars) + "…" : desc;
        j.url = canonicalUrl(raw.url());
        j.salaryText = raw.salaryText();
        // Adzuna sometimes returns timestamps a few hours in the future; never show "posted in 4 hours".
        Instant now = Instant.now();
        j.postedAt = raw.postedAt() != null && raw.postedAt().isAfter(now) ? now : raw.postedAt();
        j.sources = new ArrayList<>(List.of(raw.source()));
        j.sourceKey = raw.source() + ":" + (raw.sourceId() == null ? "" : raw.sourceId());
        j.ruleCities = new ArrayList<>(CityAliases.fromLocation(j.location));
        // Aggregators here search the India market only; company boards list jobs worldwide.
        if (ATS_SOURCES.contains(raw.source()) && CityAliases.indiaEvidence(j.location) == CityAliases.India.UNRESOLVED) {
            j.indiaUnverified = true;
        }
        j.normCompany = normCompany(j.company);
        j.normTitle = normTitle(j.title);
        return j;
    }

    /** Strip HTML tags/entities and collapse whitespace. Adzuna titles contain <strong> tags, for example. */
    public static String cleanText(String s) {
        if (s == null) return "";
        return Jsoup.parse(s).text().replaceAll("\\s+", " ").trim();
    }

    /** "Acme Technologies Pvt. Ltd." -> "acme" */
    public static String normCompany(String company) {
        String c = NON_ALNUM.matcher(company.toLowerCase().replace("&", " and ")).replaceAll(" ");
        c = COMPANY_SUFFIX.matcher(c).replaceAll(" ");
        c = c.replaceAll("\\s+", " ").trim();
        return c.isEmpty() ? company.toLowerCase().trim() : c;
    }

    /** Lowercase, drop punctuation and bracketed bits like "(Remote)", sort words, so word order doesn't matter. */
    public static String normTitle(String title) {
        String t = title.toLowerCase().replaceAll("\\([^)]*\\)|\\[[^]]*]", " ");
        t = NON_ALNUM.matcher(t).replaceAll(" ");
        return Arrays.stream(t.split("\\s+")).filter(w -> !w.isBlank()).sorted().collect(Collectors.joining(" "));
    }

    /** Drop tracking params, fragments and trailing slashes so the same link always compares equal. */
    public static String canonicalUrl(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            URI u = URI.create(url.trim());
            String query = u.getRawQuery();
            String kept = query == null ? null : Arrays.stream(query.split("&"))
                    .filter(p -> !TRACKING_PARAMS.contains(p.split("=", 2)[0].toLowerCase()))
                    .collect(Collectors.joining("&"));
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            return u.getScheme().toLowerCase() + "://" + u.getRawAuthority().toLowerCase() + path
                    + (kept == null || kept.isEmpty() ? "" : "?" + kept);
        } catch (Exception e) {
            return url.trim();
        }
    }

    public static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
