package in.beep.jobs.tag;

import in.beep.jobs.model.Job;
import in.beep.jobs.model.JobTags;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TagRulesTest {

    /** The location field is built from the given cities, so the rule's own location parse sees them. */
    private static Job job(String title, String desc, String... ruleCities) {
        Job j = new Job();
        j.title = title;
        j.company = "Acme";
        j.location = ruleCities.length == 0 ? "India" : String.join(", ", ruleCities);
        j.description = desc;
        j.ruleCities = List.of(ruleCities);
        return j;
    }

    private static JobTags tags(String fresher, String jobType, List<String> cities, List<String> skills) {
        return new JobTags("yes", "Software Engineering", cities, "unknown", jobType, fresher,
                null, null, null, null, skills, List.of(), "high");
    }

    private static JobTags ok() { return tags("yes", "full_time", List.of("Pune"), List.of()); }

    @Test
    void experienceInTextOverridesModel() {
        var r = TagRules.check(job("Mechanical Engineer", "Bachelor's degree. 3-15 years of experience as a Rotating Engineer.", "Pune"),
                tags("unclear", "full_time", List.of("Pune"), List.of()));
        assertEquals("no", r.tags().fresherFit());
        assertEquals(3, r.tags().expMinYears());
        assertEquals(15, r.tags().expMaxYears());

        assertEquals("no", TagRules.check(job("Analyst", "Minimum 2 years experience in Excel", "Pune"), ok()).tags().fresherFit());
        assertEquals("no", TagRules.check(job("Dev", "Experience: 4+ yrs in Java", "Pune"), ok()).tags().fresherFit());
        assertEquals("yes", TagRules.check(job("Dev", "0-1 years of experience. Freshers can apply", "Pune"),
                tags("unclear", "full_time", List.of("Pune"), List.of())).tags().fresherFit());
    }

    @Test
    void rangesThatIncludeFreshersAreNotSenior() {
        JobTags unclear = tags("unclear", "full_time", List.of("Pune"), List.of());
        for (String d : List.of(
                "Education Graduation / 12th Experience Fresher – 2 years of experience (SCM, audit)",
                "Entry-level role with up to 2 years of experience in application support",
                "Qualification 12th Pass Age Limit Maximum 29 Years Experience Experienced Candidates Can Apply",
                "About 2 years’ experience (strong freshers and interns welcome)")) {
            assertNotEquals("no", TagRules.check(job("Executive", d, "Pune"), unclear).tags().fresherFit(), d);
        }
        assertEquals("no", TagRules.check(job("Area Manager", "Manager with 5 years of experience in retail", "Pune"),
                unclear).tags().fresherFit());
    }

    @Test
    void leadGenerationIsASkillNotASeniority() {
        assertEquals("yes", TagRules.check(job("Lead Generation Specialist", "", "Jodhpur"),
                tags("yes", "full_time", List.of("Jodhpur"), List.of())).tags().fresherFit());
    }

    @Test
    void numbersThatAreNotExperienceAreIgnored() {
        for (String d : List.of("Pursuing a 3 year degree", "We have 20 years in business and 500 clients",
                "Age 18-25 years", "6 month internship with a 2 year bond")) {
            assertEquals("yes", TagRules.check(job("Trainee", d, "Pune"), ok()).tags().fresherFit(), d);
        }
    }

    @Test
    void seniorTitlesAreNeverFresherRoles() {
        for (String t : List.of("Sr. Software Development Engineer", "Senior Analyst", "Tech Lead - Java",
                "Consultant Orthopaedic Surgeon", "Consultant - Emergency Medicine", "Solutions Architect")) {
            assertEquals("no", TagRules.check(job(t, "", "Pune"), ok()).tags().fresherFit(), t);
        }
        for (String t : List.of("Associate Consultant - Supply Chain", "Graduate Engineer Trainee",
                "Leadership Development Intern", "Software Engineer Intern", "SOC Technical Project Lead Intern")) {
            assertEquals("yes", TagRules.check(job(t, "", "Pune"), ok()).tags().fresherFit(), t);
        }
    }

    @Test
    void internTitleMeansInternship() {
        assertEquals("internship", TagRules.check(job("Marketing Intern", "", "Pune"), ok()).tags().jobType());
        assertEquals("full_time", TagRules.check(job("Internal Auditor", "", "Pune"), ok()).tags().jobType());
    }

    @Test
    void locationFieldWinsWhenModelDisagrees() {
        var r = TagRules.check(job("Dev", "", "Bengaluru"), tags("yes", "full_time", List.of("India (unspecified)"), List.of()));
        assertEquals(List.of("Bengaluru"), r.tags().cities());
        // Agreement or remote: untouched
        assertEquals(List.of("Pune", "Mumbai"), TagRules.check(job("Dev", "", "Pune"),
                tags("yes", "full_time", List.of("Pune", "Mumbai"), List.of())).tags().cities());
        assertEquals(List.of("Remote"), TagRules.check(job("Dev", "", "Pune"),
                tags("yes", "full_time", List.of("Remote"), List.of())).tags().cities());
        assertTrue(TagRules.check(job("Dev", ""), ok()).fixes().isEmpty());   // no location parse: trust the model
    }

    @Test
    void namedSkillsMustAppearInText() {
        var r = TagRules.check(job("Backend Developer", "Strong problem-solving, Java and MySQL databases.", "Pune"),
                tags("yes", "full_time", List.of("Pune"), List.of("Java", "DSA", "SQL", "Python", "C")));
        assertEquals(List.of("Java", "DSA", "SQL"), r.tags().skills());
        assertFalse(r.tags().impliedSkills().contains("Python"));   // invented skills are dropped, not "implied"
        assertTrue(TagRules.mentioned("Go", "senior engineer, golang"));
        assertFalse(TagRules.mentioned("C", "c++ and c# developer"));
        assertTrue(TagRules.mentioned("C", "knowledge of c, c++"));
        assertTrue(TagRules.mentioned("C++", "knowledge of c++"));
    }
}
