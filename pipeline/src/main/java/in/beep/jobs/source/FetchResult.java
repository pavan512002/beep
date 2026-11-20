package in.beep.jobs.source;

import in.beep.jobs.model.RawJob;

import java.util.ArrayList;
import java.util.List;

/**
 * What one source returned in one run. A source works in "units" (one query page, one company board):
 * knowing how many units succeeded lets the runner tell a healthy source that found nothing from a
 * failed one, and only a fully healthy source may expire its old posts.
 *
 * Failures carry a category, never an exception message: messages can contain request URLs, and
 * some sources put API keys in the URL. This record ends up in the public stats.json.
 */
public record FetchResult(List<RawJob> jobs, int unitsAttempted, int unitsOk, List<Failure> failures) {

    public record Failure(String unit, String category) {}

    /** Mutable collector used by the sources while fetching. */
    public static final class Builder {
        private final List<RawJob> jobs = new ArrayList<>();
        private final List<Failure> failures = new ArrayList<>();
        private int attempted, ok;

        public Builder ok(List<RawJob> got) {
            attempted++;
            ok++;
            jobs.addAll(got);
            return this;
        }

        public Builder failed(String unit, Throwable e) {
            attempted++;
            failures.add(new Failure(unit, Http.categorize(e)));
            return this;
        }

        public FetchResult build() {
            return new FetchResult(List.copyOf(jobs), attempted, ok, List.copyOf(failures));
        }
    }

    public static Builder builder() { return new Builder(); }
}
