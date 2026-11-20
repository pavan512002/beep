package in.beep.jobs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A cleaned, deduplicated post. This is what ends up in web/data/jobs.json. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Job {
    public String id;
    public String title;
    public String company;
    public String location;          // location text as the source gave it
    public String description;
    public String url;
    public String salaryText;
    public List<String> sources = new ArrayList<>();
    public Instant postedAt;
    public Instant firstSeenAt;
    public Instant lastSeenAt;

    public JobTags tags;
    public List<String> ruleCities = new ArrayList<>(); // cities parsed from `location` without the LLM
    public boolean lowConfidence;
    public List<String> fixes;          // what the rule checks corrected after the LLM (TagRules)
    public String tagModel;
    public String promptVersion;
    /** Tagger.cacheKey of the text the tags were made from; differs from today's key when the post changed. */
    public String tagKey;
    /** True when a company-board location gave no evidence of India (e.g. just "Remote"); the LLM must confirm. */
    public Boolean indiaUnverified;

    // Working fields used during dedup; not published.
    @JsonIgnore public String normCompany;
    @JsonIgnore public String normTitle;
    @JsonIgnore public String sourceKey;   // "adzuna:4567": the source's own id for the post
}
