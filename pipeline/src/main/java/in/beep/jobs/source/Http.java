package in.beep.jobs.source;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.beep.jobs.config.BeepProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Small HTTP helper: timeouts, honest User-Agent, JSON parsing, 2 retries on 429/5xx/network errors.
 *
 * Exception messages are never logged or published as they are: Spring puts the full request URL in
 * them, and Adzuna (query string) and Jooble (path) carry API keys in the URL. Use
 * {@link #categorize} for anything public and {@link #redact} for anything logged.
 */
@Component
public class Http {
    private static final Logger log = LoggerFactory.getLogger(Http.class);
    private static final int MAX_ATTEMPTS = 3;

    /** Secret values to blank out of any text we log. Filled from the source settings. */
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    private static final Pattern QUERY = Pattern.compile("(https?://[^\\s\"'?]+)\\?[^\\s\"']*");
    private static final Pattern JOOBLE_KEY = Pattern.compile("(jooble\\.org/api/)[^\\s\"'/?]+");

    private final RestClient client;
    private final ObjectMapper mapper;

    @org.springframework.beans.factory.annotation.Autowired   // the other constructor is for tests
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
        if (props.sources() != null) {
            if (props.sources().adzuna() != null) {
                registerSecret(props.sources().adzuna().appId());
                registerSecret(props.sources().adzuna().appKey());
            }
            if (props.sources().jooble() != null) registerSecret(props.sources().jooble().key());
        }
    }

    /** For tests: a stub that overrides getJson/postJson and never touches the network. */
    protected Http(ObjectMapper mapper) {
        this.client = null;
        this.mapper = mapper;
    }

    public static void registerSecret(String value) {
        if (value != null && value.length() >= 4) SECRETS.add(value);
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
                    throw new NonJsonException(host);
                }
                return mapper.readTree(text);
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                if (status != 429 && status < 500) throw e;   // 4xx other than 429: retrying won't help
                last = e;
            } catch (RuntimeException e) {
                last = e;
            } catch (Exception e) {
                last = new RuntimeException(e);
            }
            log.warn("{} attempt {}/{} failed: {}", host, attempt, MAX_ATTEMPTS, categorize(last));
            sleep(1000L * attempt * attempt);
        }
        throw last;
    }

    /** A short, public-safe reason: http-4xx, http-5xx, rate-limited, timeout, network, non-json or other. */
    public static String categorize(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof RestClientResponseException r) {
                int s = r.getStatusCode().value();
                return s == 429 ? "rate-limited" : s >= 500 ? "http-5xx" : "http-4xx";
            }
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) return "timeout";
            if (t instanceof NonJsonException || t instanceof JsonProcessingException) return "non-json";
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ResourceAccessException || t instanceof java.io.IOException) return "network";
        }
        return "other";
    }

    /** Text safe to log: known secret values, URL query strings and the Jooble key path are blanked. */
    public static String redact(String text) {
        if (text == null) return null;
        String out = text;
        for (String s : SECRETS) out = out.replace(s, "***");
        out = QUERY.matcher(out).replaceAll("$1?***");
        out = JOOBLE_KEY.matcher(out).replaceAll("$1***");
        return out;
    }

    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** The server answered, but not with JSON. */
    static final class NonJsonException extends IllegalStateException {
        NonJsonException(String host) { super("Non-JSON response from " + host); }
    }
}
