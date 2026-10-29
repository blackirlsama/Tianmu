package com.tianmu.agent.harness.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.*;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** Read-only, bounded view of ended spans. Authorization is enforced before calling read(). */
public final class RunTraceQuery implements SpanProcessor {
    public record Result(List<TraceEnvelope> spans, String source, boolean truncated) {}
    private static final int MAX_NODES = 2000;
    private final Cache<String, TraceEnvelope> recent = Caffeine.newBuilder()
            .maximumSize(20000).expireAfterWrite(Duration.ofHours(1)).build();
    private final TraceProperties properties;
    private final ObjectMapper json;

    public RunTraceQuery(TraceProperties properties, ObjectMapper json) {
        this.properties = properties; this.json = json;
    }
    @Override public void onStart(Context parent, ReadWriteSpan span) {}
    @Override public boolean isStartRequired() { return false; }
    @Override public boolean isEndRequired() { return true; }
    @Override public void onEnd(ReadableSpan span) {
        try {
        var envelope = TraceEnvelope.from(span.toSpanData(), "Tianmu-operations-agent", "0.7.0");
        if (envelope.attributes().get("run.id") instanceof String)
            recent.put(envelope.traceId() + "/" + envelope.spanId(), envelope);
        } catch (RuntimeException ignored) { /* Query availability must not change business execution. */ }
    }
    public Result read(UUID runId, String traceId) {
        Map<String, TraceEnvelope> records = new HashMap<>();
        recent.asMap().values().stream().filter(span -> matches(span, runId, traceId))
                .forEach(span -> records.put(span.traceId() + "/" + span.spanId(), span));
        String source = "recent";
        boolean truncated = false;
        if (records.isEmpty() && "file".equals(properties.sink())) {
            source = "archive";
            Path archive = properties.directory().resolve("archive");
            long scannedBytes = 0;
            for (int i = 0; i <= Math.min(properties.archiveBackups(), 20); i++) {
                Path path = archive.resolve(i == 0 ? "spans.jsonl" : "spans." + i + ".jsonl");
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var reader = Files.newBufferedReader(path)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        scannedBytes += line.length() * 3L;
                        if (scannedBytes > 64L * 1024 * 1024) { truncated = true; break; }
                        if (line.length() > properties.maxRecordBytes() || !line.contains(runId.toString())) continue;
                        try {
                            TraceEnvelope span = json.readValue(line, TraceEnvelope.class);
                            if (matches(span, runId, traceId)) records.put(span.traceId() + "/" + span.spanId(), span);
                        } catch (IOException | RuntimeException ignored) { truncated = true; }
                    }
                } catch (NoSuchFileException ignored) { /* Archive rotation raced with this read. */ }
                catch (IOException ignored) { truncated = true; }
                if (scannedBytes > 64L * 1024 * 1024) break;
            }
        }
        if (records.size() > MAX_NODES) truncated = true;
        return new Result(records.values().stream().sorted(Comparator.comparingLong(TraceEnvelope::startTimeUnixNano)
                .thenComparing(TraceEnvelope::spanId)).limit(MAX_NODES).toList(), source, truncated);
    }
    private boolean matches(TraceEnvelope span, UUID runId, String traceId) {
        return span != null && span.attributes() != null && runId.toString().equals(span.attributes().get("run.id"))
                && (traceId == null || traceId.equals(span.traceId()) || traceId.equals(span.attributes().get("run.trace_id")));
    }
}
