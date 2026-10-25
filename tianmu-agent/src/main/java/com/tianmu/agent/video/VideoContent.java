package com.tianmu.agent.video;

import java.time.Instant;
import java.util.*;

/** Playback times are milliseconds; these are sampled model observations, not verified causes. */
public final class VideoContent {
    private VideoContent() {}
    public record Source(long videoId, String sourceVersion, long durationMs) {}
    public record Frame(long atMs, String jpegBase64) {}
    public record Media(Source source, long startMs, long endMs, List<Frame> frames, String audioBase64) {}
    public record Moment(long atMs, String observation) {}
    public record Facts(String kind, long videoId, String sourceVersion, long startMs, long endMs,
                        String summary, List<Moment> moments, String transcript, String coverage,
                        List<Long> sampledAtMs, String model, Instant analyzedAt) {
        public Facts {
            if (!"video-content-v1".equals(kind) || videoId <= 0 || sourceVersion == null || sourceVersion.length() > 128
                    || startMs < 0 || endMs <= startMs || summary == null || summary.isBlank() || summary.length() > 4000
                    || transcript == null || transcript.length() > 12000 || model == null || model.length() > 160)
                throw new IllegalArgumentException("Invalid video evidence");
            moments = List.copyOf(moments); sampledAtMs = List.copyOf(sampledAtMs);
            if (moments.size() > 12 || sampledAtMs.isEmpty() || sampledAtMs.size() > 12 || analyzedAt == null
                    || !Set.of("SAMPLED_FRAMES", "SAMPLED_FRAMES_AND_AUDIO_TRANSCRIPT").contains(coverage))
                throw new IllegalArgumentException("Invalid video coverage");
            for (long at : sampledAtMs) if (at < startMs || at >= endMs) throw new IllegalArgumentException("Frame outside range");
            for (Moment moment : moments) if (!sampledAtMs.contains(moment.atMs()) || moment.observation() == null
                    || moment.observation().isBlank() || moment.observation().length() > 600)
                throw new IllegalArgumentException("Unanchored video observation");
        }
    }
}
