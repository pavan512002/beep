package in.beep.jobs.model;

import java.time.Instant;

/** A post exactly as one source returned it, before cleaning. */
public record RawJob(
        String source,
        String sourceId,
        String title,
        String company,
        String location,
        String description,
        String url,
        Instant postedAt,
        String salaryText) {}
