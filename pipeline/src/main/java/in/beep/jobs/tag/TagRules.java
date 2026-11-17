package in.beep.jobs.tag;

import in.beep.jobs.model.Job;
import in.beep.jobs.model.JobTags;
import in.beep.jobs.normalize.CityAliases;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic checks run after the LLM (and on cached tags). The LLM reads loosely; these rules
 * enforce what the text and the structured fields say for certain. Every change is recorded in
 * {@link Result#fixes()} so we can count and show what the rules corrected.
 */
public final class TagRules {
    private TagRules() {}

    public record Result(JobTags tags, List<String> fixes) {}

    /** Senior titles. Overridden only when the text explicitly welcomes freshers. */
    static final Pattern SENIOR_TITLE = Pattern.compile(
            "\\b(senior|sr\\.?|lead(?!\\s*gen)|principal|staff engineer|director|head of|vice president|vp|architect)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Specialist doctors: "freshers may apply" there means newly qualified specialists, not graduates. */
    static final Pattern SPECIALIST_DOCTOR = Pattern.compile(
            "\\bconsultant\\s+(?:-\\s+)?(?:\\w+\\s+)?(surgeon|physician|radiologist|paediatrician|pediatrician|anaesthesiologist"
            + "|anesthesiologist|dermatologist|cardiologist|gynaecologist|gynecologist|orthopaedic|orthopedic|psychiatrist"
            + "|neurologist|oncologist|nephrologist|urologist|pathologist|intensivist|emergency)",
            Pattern.CASE_INSENSITIVE);

    static final Pattern INTERN_TITLE = Pattern.compile("\\bintern(ship)?s?\\b", Pattern.CASE_INSENSITIVE);

    /** Early-career words: a "Project Lead Intern" is still an intern. */
    static final Pattern EARLY_TITLE = Pattern.compile(
            "\\b(intern|internship|trainee|apprentice|fresher|freshers|graduate|entry[- ]level)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Experience requirements: "3-5 years of experience", "2+ yrs exp", "minimum 4 years experience",
     * "experience: 1 to 3 years". The word experience/exp must be close by, so "3 year degree" or
     * "20 years in business" don't count.
     */
    private static final String NUM = "(\\d{1,2})(?:\\.\\d)?";
    private static final String RANGE = NUM + "\\s*(?:\\+|plus|-|–|to)?\\s*(?:" + NUM + ")?\\s*\\+?\\s*(?:years?|yrs?)";
    static final Pattern EXP_AFTER = Pattern.compile(
            "(?:minimum|min\\.?|at least|atleast)?\\s*" + RANGE + "[^.;\\n]{0,25}?\\b(?:experience|exp)\\b", Pattern.CASE_INSENSITIVE);
    static final Pattern EXP_BEFORE = Pattern.compile(
            "\\b(?:experience|exp)\\b\\s*(?:of|:|-|required|needed)?\\s*(?:minimum|min\\.?|at least)?\\s*" + RANGE, Pattern.CASE_INSENSITIVE);

    public static Result check(Job j, JobTags t) {
        List<String> fixes = new ArrayList<>();
        String title = Objects.toString(j.title, "");
        String text = title + "\n" + Objects.toString(j.description, "");

        String fresher = t.fresherFit();
        Integer expMin = t.expMinYears(), expMax = t.expMaxYears();

        // 1. Experience stated in the visible text beats the model's guess. Never downgrade a post whose
        //    title is early-career or whose text welcomes freshers ("Fresher – 2 years", "freshers welcome").
        int[] exp = experience(text);
        boolean welcomesFreshers = EARLY_TITLE.matcher(title).find() || FRESHERS_OK.matcher(text).find();
        if (exp != null) {
            if (exp[0] >= 2 && !welcomesFreshers && !"no".equals(fresher)) {
                fresher = "no";
                fixes.add("fresherFit→no (text says " + exp[0] + "+ years)");
            } else if (exp[0] == 0 && "unclear".equals(fresher)) {
                fresher = "yes";
                fixes.add("fresherFit→yes (text says 0 years)");
            }
            if (expMin == null) { expMin = exp[0]; }
            if (expMax == null && exp[1] >= 0) { expMax = exp[1]; }
        }

        // 2. Senior titles are never fresher roles.
        if (SENIOR_TITLE.matcher(title).find() && !welcomesFreshers && !"no".equals(fresher)) {
            fresher = "no";
            fixes.add("fresherFit→no (senior title)");
        }
        if (SPECIALIST_DOCTOR.matcher(title).find() && !"no".equals(fresher)) {
            fresher = "no";
            fixes.add("fresherFit→no (specialist doctor)");
        }

        // 3. "Intern" in the title means an internship.
        String jobType = t.jobType();
        if (INTERN_TITLE.matcher(title).find() && !"internship".equals(jobType)) {
            jobType = "internship";
            fixes.add("jobType→internship (title)");
        }

        // 4. Adzuna's location field is structured; when the model's cities don't include it, trust the field.
        //    Parse this post's own location (j.ruleCities can include cities of merged duplicates).
        List<String> cities = t.cities() == null ? List.of() : t.cities();
        List<String> rule = CityAliases.fromLocation(j.location);
        boolean remoteOnly = cities.equals(List.of(CityAliases.REMOTE));
        String loc = Objects.toString(j.location, "").toLowerCase(Locale.ROOT);
        // "Bhatpar Rani, Deoria" + model says Deoria: fine, the district is named in the field.
        boolean modelCityInField = cities.stream().anyMatch(c -> rule.contains(c) || loc.contains(c.toLowerCase(Locale.ROOT)));
        if (!rule.isEmpty() && !remoteOnly && !modelCityInField) {
            fixes.add("cities " + cities + "→" + rule + " (location field)");
            cities = rule;
        }

        // 5. "Skills the post names" must actually appear in the text. If not, drop them: a skill the
        //    model invented is not evidence that the role needs it either.
        List<String> skills = new ArrayList<>();
        List<String> implied = t.impliedSkills() == null ? List.of() : t.impliedSkills();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String s : t.skills() == null ? List.<String>of() : t.skills()) {
            if (mentioned(s, lower)) skills.add(s);
            else fixes.add("skill '" + s + "' not in text→dropped");
        }

        if (fixes.isEmpty()) return new Result(t, List.of());
        return new Result(new JobTags(t.inIndia(), t.role(), cities, t.workMode(), jobType, fresher,
                expMin, expMax, t.stipendMinInr(), t.stipendMaxInr(), skills, implied, t.confidence()), fixes);
    }

    /** Text that clearly welcomes freshers (and isn't "no freshers"). */
    static final Pattern FRESHERS_OK = Pattern.compile(
            "(?<!no )(?<!not for )\\bfreshers?\\b\\s*(?:can|may|are|welcome|also|–|-|to|/|&|and|or|\\()", Pattern.CASE_INSENSITIVE);

    /** Words just before a number that mean it is an age or an upper limit, not a minimum. */
    private static final Pattern AGE_OR_UPTO = Pattern.compile(
            "(age|aged|below|under|maximum|max\\.?|up ?to|upto|fresher[s]?\\s*[–-]|freshers? to)\\W*$", Pattern.CASE_INSENSITIVE);

    /** Smallest stated requirement as {min, max or -1}, or null if the text states none. */
    static int[] experience(String text) {
        int[] best = null;
        for (Pattern p : List.of(EXP_AFTER, EXP_BEFORE)) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                int min = Integer.parseInt(m.group(1));
                int max = m.group(2) != null ? Integer.parseInt(m.group(2)) : -1;
                if (min > 20 || (max >= 0 && max < min)) continue;   // not a realistic experience range
                // "Age limit maximum 29 years", "up to 2 years", "Fresher – 2 years": not a minimum of N years.
                String before = text.substring(Math.max(0, m.start(1) - 22), m.start(1));
                if (AGE_OR_UPTO.matcher(before).find()) {
                    if (Pattern.compile("\\bage[ds]?\\b", Pattern.CASE_INSENSITIVE).matcher(before).find()) continue;
                    min = 0;
                }
                if (best == null || min < best[0]) best = new int[] {min, max};
            }
        }
        return best;
    }

    // Ways each skill appears in post text. Skills not listed here are matched by their own name.
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
            Map.entry("DSA", List.of("dsa", "data structure", "algorithm", "problem solving", "problem-solving")),
            Map.entry("C#/.NET", List.of("c#", ".net", "dotnet")),
            Map.entry("Cloud (AWS/Azure/GCP)", List.of("aws", "azure", "gcp", "cloud")),
            Map.entry("HTML/CSS", List.of("html", "css")),
            Map.entry("Docker/Kubernetes", List.of("docker", "kubernetes", "k8s")),
            Map.entry("Django/Flask", List.of("django", "flask")),
            Map.entry("Generative AI/LLMs", List.of("genai", "generative ai", "gen ai", "llm", "gpt", "large language")),
            Map.entry("GST/Taxation", List.of("gst", "tax")),
            Map.entry("UI/UX Design", List.of("ui/ux", "ux", "user experience", "user interface", "ui design")),
            Map.entry("English Fluency", List.of("english")),
            Map.entry("Regional Language", List.of("tamil", "telugu", "kannada", "malayalam", "marathi", "bengali", "gujarati", "punjabi", "odia", "regional language")),
            Map.entry("Foreign Language", List.of("german", "french", "japanese", "spanish", "foreign language")),
            Map.entry("MS Office", List.of("ms office", "microsoft office", "word", "powerpoint", "ms-office")),
            Map.entry("Node.js", List.of("node")),
            Map.entry("REST APIs", List.of("rest", "api")),
            Map.entry("SQL", List.of("sql", "database", "oracle", "postgres")),
            Map.entry("OOP", List.of("oop", "object oriented", "object-oriented")),
            Map.entry("Test Automation", List.of("automation", "selenium")),
            Map.entry("Manual Testing", List.of("testing", "qa", "quality assurance")),
            Map.entry("Cybersecurity", List.of("security")),
            Map.entry("Embedded Systems", List.of("embedded")),
            Map.entry("Data Analysis", List.of("analys", "analytics")),
            Map.entry("Machine Learning", List.of("machine learning", "ml")),
            Map.entry("Deep Learning", List.of("deep learning", "neural")),
            Map.entry("Computer Vision", List.of("computer vision", "image")),
            Map.entry("Customer Service", List.of("customer")),
            Map.entry("Communication", List.of("communicat")),
            Map.entry("Sales", List.of("sales", "sell")),
            Map.entry("Lead Generation", List.of("lead")),
            Map.entry("Cold Calling", List.of("calling", "calls", "telecall", "telesales")),
            Map.entry("Negotiation", List.of("negotiat")),
            Map.entry("Digital Marketing", List.of("digital marketing", "marketing")),
            Map.entry("Social Media", List.of("social media", "instagram", "linkedin", "facebook")),
            Map.entry("Content Writing", List.of("content", "writing", "writer")),
            Map.entry("Copywriting", List.of("copywrit", "copy")),
            Map.entry("Project Management", List.of("project management", "project manag", "jira", "agile", "scrum")),
            Map.entry("Operations", List.of("operation")),
            Map.entry("Supply Chain", List.of("supply chain", "logistic", "inventory", "warehouse")),
            Map.entry("Data Entry", List.of("data entry", "typing")),
            Map.entry("Accounting", List.of("account", "bookkeep")),
            Map.entry("Financial Analysis", List.of("financ")),
            Map.entry("Recruitment", List.of("recruit", "hiring", "talent acquisition", "sourcing")),
            Map.entry("HR Operations", List.of("hr", "human resource")),
            Map.entry("Teaching", List.of("teach", "tutor", "faculty")),
            Map.entry("Mechanical Design", List.of("mechanical", "solidworks", "catia", "creo")),
            Map.entry("Electrical", List.of("electric")),
            Map.entry("Civil Engineering", List.of("civil")),
            Map.entry("Electronics", List.of("electronic", "vlsi", "pcb")),
            Map.entry("Manufacturing", List.of("manufactur", "production", "plant")),
            Map.entry("Healthcare", List.of("health", "medical", "hospital", "nurs", "patient", "clinic")),
            Map.entry("Hospitality", List.of("hospitality", "hotel", "guest", "restaurant")),
            Map.entry("Legal", List.of("legal", "law")),
            Map.entry("Research", List.of("research")),
            Map.entry("Market Research", List.of("market research", "research")),
            Map.entry("Business Analysis", List.of("business analy")),
            Map.entry("Statistics", List.of("statistic")),
            Map.entry("Go", List.of("golang", "go")),
            Map.entry("Cloud", List.of("cloud")));

    static boolean mentioned(String skill, String lowerText) {
        List<String> forms = ALIASES.getOrDefault(skill, List.of(skill.toLowerCase(Locale.ROOT)));
        for (String f : forms) {
            // Word start must be a boundary; the end may continue (stems like "analys", "recruit"),
            // except for very short names (c, go, r, ml, hr, ux, qa) which must stand alone.
            boolean exact = f.length() <= 3;
            String re = "(?<![a-z0-9+#.])" + Pattern.quote(f) + (exact ? "(?![a-z0-9+#])" : "");
            if (Pattern.compile(re).matcher(lowerText).find()) return true;
        }
        return false;
    }
}
