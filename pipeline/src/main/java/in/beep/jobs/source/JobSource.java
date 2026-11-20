package in.beep.jobs.source;

/**
 * One place posts come from. To add a source: implement this interface as a Spring @Component
 * and add its settings to application.yml. The runner picks it up automatically.
 */
public interface JobSource {
    /** Short id shown in the UI and stats, e.g. "adzuna". */
    String name();

    /** False when switched off in config or when its API key is missing. */
    boolean enabled();

    /**
     * Fetch posts. Catch failures per unit (one query page, one company board) and record them in
     * the result, so one bad page doesn't discard the pages that worked. Record a category only
     * ({@link FetchResult.Builder#failed}), never an exception message: it may contain an API key.
     * May still throw on a total failure; the runner records it and carries on with other sources.
     */
    FetchResult fetch();
}
