package in.beep.jobs.normalize;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CityAliasesTest {

    @Test
    void parsesOldNamesToCanonical() {
        assertThat(CityAliases.fromLocation("Bangalore, Karnataka")).containsExactly("Bengaluru");
        assertThat(CityAliases.fromLocation("Gurgaon, Haryana")).containsExactly("Gurugram");
        assertThat(CityAliases.fromLocation("Bombay")).containsExactly("Mumbai");
        // Unknown city: keep it (so dedup can't merge it with another city), but states/country aren't cities
        assertThat(CityAliases.fromLocation("Hubli, Dharwad")).containsExactly("Hubli");
        assertThat(CityAliases.fromLocation("West Bengal, India")).isEmpty();
        assertThat(CityAliases.fromLocation("India")).isEmpty();
    }

    @Test
    void longestAliasWins() {
        assertThat(CityAliases.fromLocation("Navi Mumbai, Maharashtra")).containsExactly("Navi Mumbai");
    }

    @Test
    void multipleCitiesKeepOrder() {
        assertThat(CityAliases.fromLocation("Pune / Hyderabad / Bengaluru"))
                .containsExactly("Pune", "Hyderabad", "Bengaluru");
    }

    @Test
    void countryOnlyGivesNoCity() {
        assertThat(CityAliases.fromLocation("India")).isEmpty();
        assertThat(CityAliases.fromLocation(null)).isEmpty();
    }

    @Test
    void doesNotMatchInsideOtherWords() {
        // "goa" must not match inside "Goalpara"; "ncr" not inside "increment"
        assertThat(CityAliases.fromLocation("Goalpara, Assam")).containsExactly("Goalpara");   // a town, not Goa
    }

    @Test
    void remoteIsDetected() {
        assertThat(CityAliases.fromLocation("Remote, India")).isEqualTo(List.of("Remote"));
    }

    @Test
    void canonicalizeLlmOutput() {
        assertThat(CityAliases.canonicalize("bangalore")).isEqualTo("Bengaluru");
        assertThat(CityAliases.canonicalize("India")).isEqualTo(CityAliases.UNSPECIFIED);
        assertThat(CityAliases.canonicalize("Pune (Hybrid)")).isEqualTo("Pune");
        assertThat(CityAliases.canonicalize("tiruppur")).isEqualTo("Tiruppur");
    }
}
