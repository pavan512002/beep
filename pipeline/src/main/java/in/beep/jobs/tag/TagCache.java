package in.beep.jobs.tag;

import in.beep.jobs.model.JobTags;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tags we already paid for, keyed by ClaudeTagger.cacheKey (post text + model + prompt version).
 * Persisted to state/tag-cache.json so a post is tagged once, not every day.
 */
public class TagCache {
    private final Map<String, JobTags> map;

    public TagCache(Map<String, JobTags> initial) {
        this.map = new ConcurrentHashMap<>(initial);
    }

    public JobTags get(String key) { return map.get(key); }

    public void put(String key, JobTags tags) { map.put(key, tags); }

    public Map<String, JobTags> asMap() { return map; }

    /** Keep only entries still in use, so the file doesn't grow forever. */
    public void retainOnly(java.util.Set<String> liveKeys) { map.keySet().retainAll(liveKeys); }
}
