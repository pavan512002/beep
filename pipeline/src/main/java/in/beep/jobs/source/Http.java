package in.beep.jobs.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.beep.jobs.config.BeepProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.time.Duration;

/** Small HTTP helper: timeouts, honest User-Agent, JSON parsing, 2 retries on 429/5xx/network errors. */
@Component
public class Http {
    private static final Logger log = LoggerFactory.getLogger(Http.class);
    private static final int MAX_ATTEMPTS = 3;

    private final RestClient client;
    private final ObjectMapper mapper;

    public Http(BeepProperties props, ObjectMapper mapper) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", props.userAgent())
                .defaultHeader("Accept", "application/json")
                .build();
        this.mapper = mapper;
    }

    public JsonNode getJson(URI uri) {
        return withRetry(uri.getHost(), () -> client.get().uri(uri).retrieve().body(String.class));
    }

    public JsonNode postJson(URI uri, Object body) {
        return withRetry(uri.getHost(), () -> client.post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(String.class));
    }

    private JsonNode withRetry(String host, java.util.function.Supplier<String> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                String text = call.get();
                // Some boards (e.g. Lever) occasionally answer with an HTML page instead of JSON.
                if (text == null || text.isBlank() || text.stripLeading().startsWith("<")) {
                    throw new IllegalStateException("Non-JSON response from " + host);
                }
                return mapper.readTree(text);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status != 429 && status < 500) throw e;   // 4xx other than 429: retrying won't help
                last = e;
            } catch (Exception e) {
                last = (e instanceof RuntimeException re) ? re : new RuntimeException(e);
            }
            log.warn("{} attempt {}/{} failed: {}", host, attempt, MAX_ATTEMPTS, last.getMessage());
            sleep(1000L * attempt * attempt);
        }
        throw last;
    }

    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
