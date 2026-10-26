package com.tianmu.agent.core;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/** Typed view of the existing persisted specification; the JSON contract stays unchanged. */
public sealed interface TaskConditions {
    record Metric(long videoId, LocalDate date, String metric) implements TaskConditions {}
    record Report(DanmakuTypes.Query query) implements TaskConditions {}
    record Video(long videoId, Long startMs, Long endMs) implements TaskConditions {
        public Video {
            if (videoId <= 0 || (startMs == null) != (endMs == null)
                    || startMs != null && (startMs < 0 || endMs <= startMs || endMs - startMs > 120_000))
                throw new IllegalArgumentException("Invalid video task interval");
        }
        public void check(long id, Long start, Long end) {
            if (id != videoId || startMs != null && (start == null || end == null || start < startMs || end > endMs)
                    || (start == null) != (end == null) || start != null && (start < 0 || end <= start))
                throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Video query differs from prepared task bounds");
        }
    }
    record Other(String type) implements TaskConditions {}

    static TaskConditions read(String type, Map<String, Object> values) {
        return switch (type) {
            case "video.metric" -> {
                keys(values, Set.of("video_id", "date", "metric"));
                long id = integer(values, "video_id");
                if (id <= 0 || !"bullet_count".equals(values.get("metric"))) throw new IllegalArgumentException("Invalid metric task");
                yield new Metric(id, LocalDate.parse((String) values.get("date")), (String) values.get("metric"));
            }
            case "danmaku.report", "danmaku.overview" -> {
                keys(values, Set.of("video_id", "start", "end"));
                yield new Report(new DanmakuTypes.Query(integer(values, "video_id"),
                        LocalDateTime.parse((String) values.get("start")), LocalDateTime.parse((String) values.get("end"))));
            }
            case "video.content" -> {
                boolean bounded = values.containsKey("start_ms") || values.containsKey("end_ms");
                keys(values, bounded ? Set.of("video_id", "start_ms", "end_ms") : Set.of("video_id"));
                yield new Video(integer(values, "video_id"), bounded ? integer(values, "start_ms") : null,
                        bounded ? integer(values, "end_ms") : null);
            }
            default -> new Other(type);
        };
    }
    private static long integer(Map<String, Object> values, String name) {
        if (!(values.get(name) instanceof Number number)) throw new IllegalArgumentException("Missing integer task condition");
        return new java.math.BigDecimal(number.toString()).longValueExact();
    }
    private static void keys(Map<String, Object> values, Set<String> keys) {
        if (!values.keySet().equals(keys)) throw new IllegalArgumentException("Unexpected task conditions");
    }
}
