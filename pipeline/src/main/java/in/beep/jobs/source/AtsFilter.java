package in.beep.jobs.source;

import in.beep.jobs.normalize.CityAliases;

import java.util.regex.Pattern;

/**
 * Company career boards list every role worldwide. Keep only India-based, entry-level ones.
 * Deliberately simple: the LLM's fresher-fit tag does the careful filtering later.
 */
final class AtsFilter {
    private static final Pattern ENTRY = Pattern.compile(
            "\\b(intern|internship|fresher|graduate|trainee|entry[- ]level|junior|jr\\.?|apprentice|campus|new grad|associate)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENIOR = Pattern.compile(
            "\\b(senior|sr\\.?|lead|manager|director|principal|staff|head|vp|architect)\\b",
            Pattern.CASE_INSENSITIVE);

    private AtsFilter() {}

    static boolean isEntryLevel(String title) {
        return title != null && ENTRY.matcher(title).find() && !SENIOR.matcher(title).find();
    }

    static boolean isIndia(String location) {
        if (location == null) return false;
        String l = location.toLowerCase();
        return l.contains("india") || !CityAliases.fromLocation(location).isEmpty();
    }
}
