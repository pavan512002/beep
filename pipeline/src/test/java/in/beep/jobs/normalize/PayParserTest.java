package in.beep.jobs.normalize;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class PayParserTest {
    private static Optional<PayParser.Pay> p(String s) { return PayParser.monthlyInr(s); }

    @Test
    void clearYearlyFiguresBecomeMonthly() {
        assertThat(p("INR 360000 - 420000 per year")).contains(new PayParser.Pay(30_000, 35_000));
        assertThat(p("INR 300000 per year")).contains(new PayParser.Pay(25_000, null));
        assertThat(p("3 - 4 LPA")).contains(new PayParser.Pay(25_000, 33_300));
        assertThat(p("Rs. 2.5 Lakh per annum")).contains(new PayParser.Pay(20_800, null));
    }

    @Test
    void monthlyFiguresStayMonthly() {
        assertThat(p("₹15,000 - ₹20,000 per month")).contains(new PayParser.Pay(15_000, 20_000));
        assertThat(p("Stipend: INR 12k/month for 6 months")).contains(new PayParser.Pay(12_000, null));
    }

    @Test
    void ambiguousOrUselessTextGivesNothing() {
        // Adzuna says "per year" but 15k–20k a year is not a real salary: probably monthly. Skip.
        assertThat(p("INR 15000 - 20000 per year")).isEmpty();
        assertThat(p("INR 60000 per year")).isEmpty();
        assertThat(p("INR 100000 - 1500000 per year")).isEmpty();      // 15x spread: no information
        assertThat(p("INR 0 per year")).isEmpty();
        assertThat(p("INR 50000000 per year")).isEmpty();              // ₹41L a month: not a fresher job
        assertThat(p("Not disclosed")).isEmpty();
        assertThat(p("$4,000 per month")).isEmpty();                   // not rupees
        assertThat(p("INR 20000 - 30000")).isEmpty();                   // no period
        assertThat(p(null)).isEmpty();
    }
}
