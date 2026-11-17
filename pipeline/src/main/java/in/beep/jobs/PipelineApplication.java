package in.beep.jobs;

import in.beep.jobs.config.BeepProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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

    @Bean
    CommandLineRunner runOnce(PipelineRunner runner) {
        return args -> runner.run();
    }
}
