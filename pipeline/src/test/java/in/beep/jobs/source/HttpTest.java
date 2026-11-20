package in.beep.jobs.source;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class HttpTest {

    @Test
    void redactRemovesQueryStringKeys() {
        String msg = "I/O error on GET request for \"https://api.adzuna.com/v1/api/jobs/in/search/2?app_id=abc&app_key=s3cr3t-key&what=fresher\": Read timed out";
        String out = Http.redact(msg);
        assertFalse(out.contains("s3cr3t-key"), out);
        assertFalse(out.contains("app_id=abc"), out);
        assertTrue(out.contains("api.adzuna.com/v1/api/jobs/in/search/2"), out);
    }

    @Test
    void redactRemovesJoobleKeyPath() {
        String out = Http.redact("I/O error on POST request for \"https://jooble.org/api/0f0f-1234-key\": refused");
        assertFalse(out.contains("0f0f-1234-key"), out);
        assertTrue(out.contains("jooble.org/api/***"), out);
    }

    @Test
    void redactBlanksRegisteredSecretsAnywhere() {
        Http.registerSecret("REGISTERED-SECRET-42");
        assertEquals("key was *** here", Http.redact("key was REGISTERED-SECRET-42 here"));
    }

    @Test
    void categorizeNeverReturnsAMessage() {
        assertEquals("http-5xx", Http.categorize(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "x", null, null, null)));
        assertEquals("rate-limited", Http.categorize(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "x", null, null, null)));
        assertEquals("http-4xx", Http.categorize(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "x", null, null, null)));
        assertEquals("timeout", Http.categorize(new ResourceAccessException("I/O error on GET https://x?app_key=k", new SocketTimeoutException("Read timed out"))));
        assertEquals("network", Http.categorize(new ResourceAccessException("I/O error", new java.net.ConnectException("refused"))));
        assertEquals("non-json", Http.categorize(new Http.NonJsonException("api.lever.co")));
        assertEquals("other", Http.categorize(new IllegalArgumentException("https://x?app_key=k")));
    }
}
