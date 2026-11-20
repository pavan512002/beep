package in.beep.jobs;

import in.beep.jobs.config.BeepProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Entry point. Runs the pipeline once and exits (GitHub Actions runs it daily).
 * Usage: java -jar pipeline/build/libs/pipeline.jar   (from the repo root)
 */
@SpringBootApplication
@EnableConfigurationProperties(BeepProperties.class)
public class PipelineApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(PipelineApplication.class, args)));
    }

    /** On by default; tests switch it off to check that the app wires up without running it. */
    @Bean
    @ConditionalOnProperty(name = "beep.run-on-start", havingValue = "true", matchIfMissing = true)
    CommandLineRunner runOnce(PipelineRunner runner) {
        return args -> runner.run();
    }
}
