package in.beep.jobs.normalize;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizerTest {

    @Test
    void canonicalUrlDropsTrackingAndTrailingSlash() {
        assertThat(Normalizer.canonicalUrl("https://Jobs.Example.com/apply/123/?utm_source=x&id=9#top"))
                .isEqualTo("https://jobs.example.com/apply/123?id=9");
        assertThat(Normalizer.canonicalUrl("https://example.com/a/?utm_campaign=y"))
                .isEqualTo("https://example.com/a");
    }

    @Test
    void companySuffixesAreRemoved() {
        assertThat(Normalizer.normCompany("Acme Technologies Pvt. Ltd.")).isEqualTo("acme");
        assertThat(Normalizer.normCompany("ACME Private Limited")).isEqualTo("acme");
        assertThat(Normalizer.normCompany("Tata Consultancy Services")).isEqualTo("tata consultancy");
    }

    @Test
    void titleIgnoresWordOrderAndBrackets() {
        assertThat(Normalizer.normTitle("Data Analyst - Intern (Remote)"))
                .isEqualTo(Normalizer.normTitle("Intern Data Analyst"));
    }

    @Test
    void htmlIsStripped() {
        assertThat(Normalizer.cleanText("<strong>Java</strong>&nbsp;Developer  \n Intern"))
                .isEqualTo("Java Developer Intern");
    }
}
