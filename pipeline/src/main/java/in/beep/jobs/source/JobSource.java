package in.beep.jobs.source;

import in.beep.jobs.model.RawJob;

import java.util.List;

/**
 * One place posts come from. To add a source: implement this interface as a Spring @Component
 * and add its settings to application.yml. The runner picks it up automatically.
 */
public interface JobSource {
    /** Short id shown in the UI and stats, e.g. "adzuna". */
    String name();

    /** False when switched off in config or when its API key is missing. */
    boolean enabled();

    /** Fetch posts. May throw; the runner records the error and carries on with other sources. */
    List<RawJob> fetch();
}
