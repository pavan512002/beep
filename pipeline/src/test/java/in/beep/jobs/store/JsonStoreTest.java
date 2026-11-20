package in.beep.jobs.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.beep.jobs.config.BeepProperties;
import in.beep.jobs.model.Job;
import in.beep.jobs.model.JobTags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JsonStoreTest {

    @Test
    void formulaLikeTextIsDefusedInTheCsvOnly() {
        assertThat(JsonStore.neutralizeFormula("=HYPERLINK(\"http://evil\")")).isEqualTo("'=HYPERLINK(\"http://evil\")");
        assertThat(JsonStore.neutralizeFormula("+91 98765")).isEqualTo("'+91 98765");
        assertThat(JsonStore.neutralizeFormula("-Urgent-")).isEqualTo("'-Urgent-");
        assertThat(JsonStore.neutralizeFormula("@home")).isEqualTo("'@home");
        assertThat(JsonStore.neutralizeFormula("Data Analyst")).isEqualTo("Data Analyst");
        assertThat(JsonStore.neutralizeFormula("")).isEqualTo("");
    }

    @Test
    void csvKeepsNumbersAndDefusesText(@TempDir Path root) throws Exception {
        var props = new BeepProperties(root.toString(), "t", 30, 3, 2000, new BeepProperties.Dedup(0.92), null, null);
        var store = new JsonStore(props, new ObjectMapper().findAndRegisterModules());
        Job j = new Job();
        j.id = "abc";
        j.title = "=1+1";
        j.company = "Acme, Inc";
        j.location = "Pune";
        j.url = "https://x.example/1";
        j.sources = List.of("adzuna");
        j.tags = new JobTags("yes", "Other", List.of("Pune"), "unknown", "full_time", "yes",
                0, 1, 15000, 20000, List.of(), List.of(), "high");
        store.writeCsv(List.of(j));
        String line = Files.readAllLines(store.csvFile()).get(1);
        assertThat(line).startsWith("abc,'=1+1,\"Acme, Inc\",Pune,");
        assertThat(line).contains(",15000,20000,");
        try (var files = Files.list(store.csvFile().getParent())) {
            assertThat(files.map(Path::toString)).noneMatch(f -> f.endsWith(".tmp"));
        }
    }
}
