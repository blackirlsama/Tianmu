package com.tianmu.agent.harness.trace;

import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import io.opentelemetry.api.trace.Span;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.LongAdder;

/** Request-scoped counters propagated with OTel Context, shared safely by parallel preparation calls. */
public final class TraceUsage {
    public static final ContextKey<TraceUsage> KEY = ContextKey.named("tianmu.trace.usage");
    private final LongAdder input = new LongAdder(), output = new LongAdder(), calls = new LongAdder();
    private final LongAdder unreported = new LongAdder();
    private final LongAdder external = new LongAdder();
    public static void externalCall() { TraceUsage usage = Context.current().get(KEY); if (usage != null) usage.external.increment(); }
    public long externalCalls() { return external.sum(); }
    private final LongAdder cache = new LongAdder(), miss = new LongAdder(), missCalls = new LongAdder(), reasoning = new LongAdder(), cacheCalls = new LongAdder(), reasoningCalls = new LongAdder();
    public static void record(String model, long inputTokens, long outputTokens) {
        record(model, inputTokens < 0 ? null : inputTokens, outputTokens < 0 ? null : outputTokens, null, null, null);
    }
    public static void record(String model, Long in, Long out, Long cacheHit, Long cacheMiss, Long reasoningTokens) {
        Map<String, Object> attrs = new HashMap<>(); attrs.put("model.name", model);
        attrs.put("model.usage_state", in == null && out == null ? "NOT_REPORTED" : in == null || out == null ? "PARTIAL" : "REPORTED");
        if (in != null) attrs.put("model.input_tokens", in);
        if (out != null) attrs.put("model.output_tokens", out);
        if (in != null && out != null) attrs.put("model.total_tokens", in + out);
        if (cacheHit != null) attrs.put("model.cache_hit_tokens", cacheHit);
        if (cacheMiss != null) attrs.put("model.cache_miss_tokens", cacheMiss);
        if (reasoningTokens != null) attrs.put("model.reasoning_tokens", reasoningTokens);
        TraceAttributes.apply(Span.current(), attrs);
        TraceUsage usage = Context.current().get(KEY);
        if (usage != null) {
            if (in != null && out != null) { usage.input.add(in); usage.output.add(out); usage.calls.increment(); }
            else usage.unreported.increment();
            if (cacheHit != null) { usage.cache.add(cacheHit); usage.cacheCalls.increment(); }
            if (cacheMiss != null) { usage.miss.add(cacheMiss); usage.missCalls.increment(); }
            if (reasoningTokens != null) { usage.reasoning.add(reasoningTokens); usage.reasoningCalls.increment(); }
        }
    }
    public Map<String, Object> totals() {
        Map<String, Object> totals = new HashMap<>();
        totals.put("usage.reported_calls", calls.sum()); totals.put("usage.scope", "RUN");
        totals.put("usage.external_model_calls", external.sum());
        totals.put("usage.state", calls.sum() == 0 ? "NOT_REPORTED" : unreported.sum() > 0 ? "PARTIAL" : "REPORTED");
        totals.put("usage.complete", unreported.sum() == 0 && calls.sum() > 0);
        if (calls.sum() > 0) { totals.put("usage.input_tokens", input.sum()); totals.put("usage.output_tokens", output.sum()); totals.put("usage.total_tokens", input.sum() + output.sum()); }
        if (cacheCalls.sum() > 0) totals.put("usage.cache_hit_tokens", cache.sum());
        if (missCalls.sum() > 0) totals.put("usage.cache_miss_tokens", miss.sum());
        if (reasoningCalls.sum() > 0) totals.put("usage.reasoning_tokens", reasoning.sum());
        return totals;
    }
}
