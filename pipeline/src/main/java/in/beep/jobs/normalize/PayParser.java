package in.beep.jobs.normalize;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a source's pay text ("INR 360000 - 420000 per year", "₹15,000/month", "3-4 LPA") into a
 * monthly INR range, without an LLM. Used only to fill pay the tagger left empty, and only when
 * the text is unambiguous: a wrong number on a card is worse than none.
 *
 * Adzuna labels every figure "per year", but small ones are clearly monthly stipends
 * ("INR 15000 - 20000 per year"). A yearly figure under ₹1,00,000 could be either, so it is skipped.
 */
public final class PayParser {
    public record Pay(int minMonthly, Integer maxMonthly) {}

    static final int MIN_MONTHLY = 1_000;
    static final int MAX_MONTHLY = 3_00_000;
    static final int MIN_CLEAR_YEARLY = 1_00_000;   // below this, "per year" may really mean per month
    static final int MAX_SPREAD = 4;                 // a 1L–15L "range" says nothing useful

    private static final Pattern RUPEES = Pattern.compile("inr|₹|\\brs\\.?|rupee|lpa|lakh|lac\\b|lacs\\b");
    private static final Pattern AMOUNT = Pattern.compile(
            "(\\d+(?:,\\d{2,3})*(?:\\.\\d+)?)\\s*(k|l|lakhs?|lacs?|lpa)?(?![a-z])");
    private static final Pattern YEARLY = Pattern.compile("lpa|per\\s*(year|annum)|/\\s*(yr|year|annum)|annual|p\\.?\\s?a\\.?(?![a-z])|yearly");
    private static final Pattern MONTHLY = Pattern.compile("per\\s*month|/\\s*(month|mo|m)(?![a-z])|monthly|\\bp\\.?\\s?m\\.?(?![a-z])");

    private PayParser() {}

    public static Optional<Pay> monthlyInr(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String t = text.toLowerCase(Locale.ROOT);
        if (!RUPEES.matcher(t).find()) return Optional.empty();          // other currency, or no pay at all

        boolean yearly = YEARLY.matcher(t).find();
        boolean monthly = MONTHLY.matcher(t).find();
        if (yearly == monthly) return Optional.empty();                  // period unknown (or contradictory)

        List<Long> amounts = new ArrayList<>();
        Matcher m = AMOUNT.matcher(t);
        while (m.find() && amounts.size() < 2) {
            double v = Double.parseDouble(m.group(1).replace(",", ""));
            String unit = m.group(2);
            if (unit != null) v *= unit.equals("k") ? 1_000 : 1_00_000;
            else if (t.contains("lpa") || t.matches(".*\\b(lakhs?|lacs?)\\b.*")) v *= 1_00_000;   // "3 - 4 LPA"
            amounts.add(Math.round(v));
        }
        if (amounts.isEmpty() || amounts.get(0) <= 0) return Optional.empty();
        long lo = amounts.get(0);
        Long hi = amounts.size() > 1 && amounts.get(1) > 0 ? amounts.get(1) : null;
        if (hi != null && hi < lo) hi = null;                             // "₹15,000/month for 6 months"
        if (hi != null && hi > lo * MAX_SPREAD) return Optional.empty();

        if (yearly) {
            if (lo < MIN_CLEAR_YEARLY) return Optional.empty();
            lo = lo / 12;
            if (hi != null) hi = hi / 12;
        }
        if (lo < MIN_MONTHLY || lo > MAX_MONTHLY || (hi != null && hi > MAX_MONTHLY)) return Optional.empty();
        return Optional.of(new Pay(roundTo100(lo), hi == null ? null : roundTo100(hi)));
    }

    private static int roundTo100(long v) {
        return (int) (Math.round(v / 100.0) * 100);
    }
}
