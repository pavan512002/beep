package in.beep.jobs.tag;

import in.beep.jobs.config.BeepProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds up real token usage from every API response, per provider, and turns it into dollars twice:
 * what we actually paid (0 on a free tier) and what the same tokens cost on the provider's paid plan.
 */
public class CostTracker {

    private static class Usage {
        BeepProperties.Provider cfg;
        long calls, inputTokens, outputTokens;
    }

    private final Map<String, Usage> byProvider = new LinkedHashMap<>();
    private long failedPosts;

    public synchronized void add(LlmClient client, long in, long out) {
        Usage u = byProvider.computeIfAbsent(client.label(), k -> new Usage());
        u.cfg = client.config();
        u.calls++;
        u.inputTokens += in;
        u.outputTokens += out;
    }

    public synchronized void failed(int posts) { failedPosts += posts; }

    public synchronized long failedPosts() { return failedPosts; }

    public synchronized long calls() { return byProvider.values().stream().mapToLong(u -> u.calls).sum(); }

    public synchronized long inputTokens() { return byProvider.values().stream().mapToLong(u -> u.inputTokens).sum(); }

    public synchronized long outputTokens() { return byProvider.values().stream().mapToLong(u -> u.outputTokens).sum(); }

    /** What this run actually cost. */
    public synchronized double costUsd() {
        return byProvider.values().stream().mapToDouble(u -> dollars(u, u.cfg.price())).sum();
    }

    /** What this run would have cost on the providers' paid plans. */
    public synchronized double paidEquivalentUsd() {
        return byProvider.values().stream().mapToDouble(u -> dollars(u, u.cfg.paidPrice())).sum();
    }

    /** Per "provider:model": calls, tokens and both costs, for stats.json. */
    public synchronized Map<String, Object> breakdown() {
        Map<String, Object> out = new LinkedHashMap<>();
        byProvider.forEach((label, u) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", u.calls);
            m.put("inputTokens", u.inputTokens);
            m.put("outputTokens", u.outputTokens);
            m.put("costUsd", dollars(u, u.cfg.price()));
            m.put("paidEquivalentUsd", dollars(u, u.cfg.paidPrice()));
            out.put(label, m);
        });
        return out;
    }

    private static double dollars(Usage u, BeepProperties.Price p) {
        if (p == null) return 0;
        return (u.inputTokens * p.input() + u.outputTokens * p.output()) / 1_000_000.0;
    }
}
