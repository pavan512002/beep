package in.beep.jobs.tag;

import com.anthropic.models.messages.Usage;
import in.beep.jobs.config.BeepProperties;

import java.util.concurrent.atomic.AtomicLong;

/** Adds up real token usage from every API response and turns it into dollars. */
public class CostTracker {
    // Anthropic cache pricing multipliers relative to the base input price.
    private static final double CACHE_WRITE_MULTIPLIER = 1.25;
    private static final double CACHE_READ_MULTIPLIER = 0.10;

    public final AtomicLong calls = new AtomicLong();
    public final AtomicLong failures = new AtomicLong();
    public final AtomicLong inputTokens = new AtomicLong();
    public final AtomicLong outputTokens = new AtomicLong();
    public final AtomicLong cacheWriteTokens = new AtomicLong();
    public final AtomicLong cacheReadTokens = new AtomicLong();

    public void add(Usage u) {
        calls.incrementAndGet();
        inputTokens.addAndGet(u.inputTokens());
        outputTokens.addAndGet(u.outputTokens());
        cacheWriteTokens.addAndGet(u.cacheCreationInputTokens().orElse(0L));
        cacheReadTokens.addAndGet(u.cacheReadInputTokens().orElse(0L));
    }

    public double costUsd(BeepProperties.Price price) {
        if (price == null) return 0;
        return (inputTokens.get() * price.input()
                + cacheWriteTokens.get() * price.input() * CACHE_WRITE_MULTIPLIER
                + cacheReadTokens.get() * price.input() * CACHE_READ_MULTIPLIER
                + outputTokens.get() * price.output()) / 1_000_000.0;
    }
}
