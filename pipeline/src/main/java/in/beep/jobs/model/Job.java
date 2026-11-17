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
    public List<String> fixes;          // what the rule checks corrected after the LLM (TagRules)                       // LLM city disagrees with ruleCities
    public String tagModel;
    public String promptVersion;

    // Working fields used during dedup; not published.
    @JsonIgnore public String normCompany;
    @JsonIgnore public String normTitle;
}
