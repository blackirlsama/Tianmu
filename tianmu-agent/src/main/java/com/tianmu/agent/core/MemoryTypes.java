package com.tianmu.agent.core;

import java.time.Instant;
import java.util.*;

public final class MemoryTypes {
    private MemoryTypes() {}
    public record Message(UUID id, UUID runId, long sequence, String role, String text, Instant at) {}
    public record Focus(String request, UUID sourceRunId, String referenceVideoId) {
        public Focus {
            if (request == null || request.isBlank() || request.length() > 500 || sourceRunId == null)
                throw new IllegalArgumentException("Invalid conversation focus");
        }
    }
    public record View(long version, String olderSummary, List<Message> recent, boolean truncated, String boundary,
                       RequestContext environment, Focus focus) {
        public View(long version, String summary, List<Message> recent, boolean truncated, String boundary) {
            this(version, summary, recent, truncated, boundary, null, null);
        }
        public View { recent = List.copyOf(recent); }
        public View withContext(RequestContext environment, Focus focus) {
            return new View(version, olderSummary, recent, truncated, boundary, environment, focus);
        }
        public static View empty() { return new View(0, "", List.of(), false, ""); }
    }
}
