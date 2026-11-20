package in.beep.jobs;

import in.beep.jobs.source.JobSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Builds the real Spring context (every bean, real config) without running the pipeline.
 * Catches wiring mistakes, e.g. a component with two constructors and no @Autowired, which unit
 * tests that call constructors directly never see but which stop the daily run at startup.
 */
@SpringBootTest(properties = "beep.run-on-start=false")
class PipelineApplicationTest {
    @Autowired PipelineRunner runner;
    @Autowired List<JobSource> sources;

    @Test
    void contextStartsWithEverySource() {
        assertThat(runner).isNotNull();
        assertThat(sources).extracting(JobSource::name)
                .contains("adzuna", "jooble", "greenhouse", "lever", "ashby");
    }
}
