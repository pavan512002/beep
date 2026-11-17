package in.beep.jobs.tag;

import in.beep.jobs.model.JobTags;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tags already produced, keyed by Tagger.cacheKey (prompt version + post text).
 * Persisted to state/tag-cache.json so a post is tagged once, not every day.
 * The key leaves out the model on purpose: falling back from Groq to Gemini must not re-tag everything.
 */
public class TagCache {

    /** Tags plus the "provider:model" that produced them. */
    public record Entry(JobTags tags, String model) {}

    private final Map<String, Entry> map;

    public TagCache(Map<String, Entry> initial) {
        this.map = new ConcurrentHashMap<>(initial);
    }

    public Entry get(String key) { return map.get(key); }

    public void put(String key, Entry e) { map.put(key, e); }

    public Map<String, Entry> asMap() { return map; }

    /** Keep only entries still in use, so the file doesn't grow forever. */
    public void retainOnly(Set<String> liveKeys) { map.keySet().retainAll(liveKeys); }
}
