package com.tianmu.agent.application;

import java.util.Set;
import java.util.HashSet;

/** Explicit ceilings: a newly registered tool is never implicitly granted to an existing task. */
public final class TaskCapabilities {
    private TaskCapabilities() {}
    public static Set<String> toolsFor(String taskType, Set<String> registered) {
        Set<String> ceiling = switch (taskType) {
            case "creator.draft" -> Set.of("list_skills", "read_skill", "read_result", "analyze_video_content", "prepare_creator_interactions");
            case "video.metric" -> Set.of("list_skills", "read_skill", "read_result", "query_video_metrics");
            case "video.content" -> Set.of("list_skills", "read_skill", "read_result", "analyze_video_content");
            case "audience.report" -> Set.of("list_skills", "read_skill", "read_result", "query_audience", "analyze_video_content", "create_danmaku_report");
            case "danmaku.report" -> Set.of("list_skills", "read_skill", "read_result", "query_danmaku", "analyze_video_content", "create_danmaku_report");
            case "danmaku.overview" -> Set.of("list_skills", "read_skill", "read_result", "query_danmaku");
            case "comments.analysis" -> Set.of("list_skills", "read_skill", "read_result", "query_video_comments", "analyze_video_content");
            default -> Set.of();
        };
        Set<String> available = new HashSet<>(ceiling);
        available.retainAll(registered);
        return Set.copyOf(available);
    }
}
