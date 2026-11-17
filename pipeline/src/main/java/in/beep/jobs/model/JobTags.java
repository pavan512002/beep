package in.beep.jobs.model;

import java.util.List;

/**
 * What the LLM returns for one post. Allowed values are enforced by
 * resources/prompts/tag-schema.json (structured output), not by Java enums,
 * so adding a role or city only needs a schema + prompt edit.
 */
public record JobTags(
        String role,
        List<String> cities,
        String workMode,      // onsite | hybrid | remote | unknown
        String jobType,       // internship | full_time | unknown
        String fresherFit,    // yes | no | unclear
        Integer expMinYears,
        Integer expMaxYears,
        Integer stipendMinInr, // monthly INR, null if not stated
        Integer stipendMaxInr,
        List<String> skills,
        String confidence) {}  // high | medium | low
