package com.tianmu.agent.harness.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RunTraceQueryTest {
    @TempDir Path directory;
    @Test void archiveFallbackFiltersRunAndTraceAndDeduplicatesReplay() throws Exception {
        var json = new ObjectMapper();
        var runId = UUID.randomUUID();
        var other = UUID.randomUUID();
        var props = new TraceProperties("file", directory, 10000, 67108864, 65536, 5242880, 3, null);
        var first = span(runId, "trace-a", "span-a");
        Files.createDirectories(directory.resolve("archive"));
        Files.writeString(directory.resolve("archive/spans.jsonl"), json.writeValueAsString(first) + "\n"
                + json.writeValueAsString(first) + "\n" + json.writeValueAsString(span(other, "trace-a", "span-b")) + "\n"
                + json.writeValueAsString(span(runId, "trace-b", "span-c")) + "\n");
        var result = new RunTraceQuery(props, json).read(runId, "trace-a");
        assertEquals("archive", result.source());
        assertEquals(1, result.spans().size());
        assertEquals("span-a", result.spans().get(0).spanId());
        assertFalse(result.truncated());
    }
    private TraceEnvelope span(UUID runId, String trace, String span) {
        return new TraceEnvelope("1.1", UUID.randomUUID().toString(), "test", "test", trace, span, "root", "agent.model", "INTERNAL",
                1, 2, 1, "UNSET", Map.of("run.id", runId.toString()));
    }
}
