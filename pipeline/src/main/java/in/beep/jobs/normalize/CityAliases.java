package in.beep.jobs.normalize;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical Indian city names and their common spellings.
 * Used two ways:
 *  1. fromLocation(): rule-based city parse of a location string (no LLM), for comparison and fallback.
 *  2. canonicalize(): clean up a city name the LLM returned, so "Bangalore" and "Bengaluru" never split a filter.
 */
public final class CityAliases {
    public static final String REMOTE = "Remote";
    public static final String UNSPECIFIED = "India (unspecified)";

    /** canonical -> aliases (all lowercase). The canonical name itself is always an alias. */
    private static final Map<String, List<String>> CITIES = new LinkedHashMap<>();
    static {
        add("Bengaluru", "bangalore", "bengaluru", "bangaluru", "blr");
        add("Mumbai", "mumbai", "bombay");
        add("Navi Mumbai", "navi mumbai");
        add("Thane", "thane");
        add("Delhi", "delhi", "new delhi");
        add("Gurugram", "gurugram", "gurgaon");
        add("Noida", "noida", "greater noida");
        add("Delhi NCR", "delhi ncr", "ncr");
        add("Ghaziabad", "ghaziabad");
        add("Faridabad", "faridabad");
        add("Hyderabad", "hyderabad", "secunderabad");
        add("Chennai", "chennai", "madras");
        add("Pune", "pune", "poona");
        add("Kolkata", "kolkata", "calcutta");
        add("Ahmedabad", "ahmedabad", "amdavad");
        add("Gandhinagar", "gandhinagar");
        add("Vadodara", "vadodara", "baroda");
        add("Surat", "surat");
        add("Jaipur", "jaipur");
        add("Chandigarh", "chandigarh");
        add("Mohali", "mohali");
        add("Lucknow", "lucknow");
        add("Indore", "indore");
        add("Bhopal", "bhopal");
        add("Nagpur", "nagpur");
        add("Nashik", "nashik");
        add("Coimbatore", "coimbatore");
        add("Kochi", "kochi", "cochin", "ernakulam");
        add("Thiruvananthapuram", "thiruvananthapuram", "trivandrum");
        add("Mysuru", "mysuru", "mysore");
        add("Mangaluru", "mangaluru", "mangalore");
        add("Visakhapatnam", "visakhapatnam", "vizag");
        add("Vijayawada", "vijayawada");
        add("Bhubaneswar", "bhubaneswar");
        add("Patna", "patna");
        add("Ranchi", "ranchi");
        add("Guwahati", "guwahati");
        add("Dehradun", "dehradun");
        add("Kanpur", "kanpur");
        add("Madurai", "madurai");
        add("Trichy", "trichy", "tiruchirappalli");
        add("Goa", "goa", "panaji");
        add(REMOTE, "remote", "work from home", "wfh", "anywhere in india");
    }

    private static final Map<String, String> ALIAS_TO_CANONICAL = new HashMap<>();
    private static final List<String> ALIASES_LONGEST_FIRST;
    static {
        CITIES.forEach((canon, aliases) -> aliases.forEach(a -> ALIAS_TO_CANONICAL.put(a, canon)));
        ALIASES_LONGEST_FIRST = new ArrayList<>(ALIAS_TO_CANONICAL.keySet());
        ALIASES_LONGEST_FIRST.sort(Comparator.comparingInt(String::length).reversed());
    }

    private CityAliases() {}

    private static void add(String canonical, String... aliases) {
        List<String> list = new ArrayList<>(List.of(aliases));
        if (!list.contains(canonical.toLowerCase())) list.add(canonical.toLowerCase());
        CITIES.put(canonical, list);
    }

    /** All canonical names, in a stable order (used by the prompt and tests). */
    public static List<String> canonicalNames() { return List.copyOf(CITIES.keySet()); }

    /** Rule-based parse: "Bangalore, Karnataka" -> [Bengaluru]; "India" -> []. Longest alias wins ("Navi Mumbai" before "Mumbai"). */
    public static List<String> fromLocation(String location) {
        if (location == null || location.isBlank()) return List.of();
        String text = " " + location.toLowerCase() + " ";
        TreeMap<Integer, String> found = new TreeMap<>();   // position -> canonical, keeps source order
        for (String alias : ALIASES_LONGEST_FIRST) {
            Matcher m = Pattern.compile("(?<![a-z])" + Pattern.quote(alias) + "(?![a-z])").matcher(text);
            while (m.find()) {
                found.putIfAbsent(m.start(), ALIAS_TO_CANONICAL.get(alias));
                // blank out the match so "mumbai" inside "navi mumbai" isn't counted again
                text = text.substring(0, m.start()) + " ".repeat(alias.length()) + text.substring(m.end());
                m = Pattern.compile("(?<![a-z])" + Pattern.quote(alias) + "(?![a-z])").matcher(text);
            }
        }
        return found.values().stream().distinct().toList();
    }

    /** Map an LLM-returned city to its canonical name. Unknown cities are kept, title-cased. */
    public static String canonicalize(String city) {
        if (city == null || city.isBlank()) return null;
        String key = city.trim().toLowerCase();
        if (key.equals("india") || key.equals(UNSPECIFIED.toLowerCase())) return UNSPECIFIED;
        String canon = ALIAS_TO_CANONICAL.get(key);
        if (canon != null) return canon;
        // e.g. "Bangalore, Karnataka" or "Pune (Hybrid)"
        List<String> parsed = fromLocation(city);
        if (!parsed.isEmpty()) return parsed.get(0);
        return titleCase(city.trim());
    }

    private static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase().split("\\s+")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
}
