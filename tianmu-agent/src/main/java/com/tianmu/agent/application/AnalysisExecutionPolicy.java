package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.TaskTypes.Specification;
import java.util.*;
import static com.tianmu.agent.core.AnalysisContract.Source.*;

/** Application policy shared by discovery, execution admission and delivery. */
public final class AnalysisExecutionPolicy {
    private AnalysisExecutionPolicy() {}
    public static boolean permits(String tool, Specification spec) {
        if (spec == null || spec.analysis() == null) return true;
        var contract = spec.analysis();
        return switch (tool) {
            case "analyze_video_content" -> contract.includes(VIDEO);
            case "query_video_comments" -> contract.includes(COMMENT) && "comments.analysis".equals(spec.type());
            case "query_danmaku" -> contract.includes(DANMAKU) && Set.of("danmaku.report", "danmaku.overview").contains(spec.type());
            case "query_video_metrics" -> contract.includes(DANMAKU) && "video.metric".equals(spec.type());
            case "query_audience" -> !contract.textSources().isEmpty() && "audience.report".equals(spec.type());
            case "create_danmaku_report" -> contract.reportRequired();
            default -> true;
        };
    }
    public static void requireSource(Specification spec, AnalysisContract.Source source) {
        if (spec != null && spec.analysis() != null && !spec.analysis().includes(source))
            throw new AgentException("TASK_CONSTRAINT_VIOLATION", "This source is outside the frozen analysis request");
    }
    public static int maxTurns(Specification spec, int configured, int modelCalls) {
        return spec != null && Set.of("danmaku.report", "audience.report", "comments.analysis").contains(spec.type())
                ? Math.max(configured, Math.min(32, modelCalls - 1)) : configured;
    }
    public static void requireComplete(Specification spec, List<Evidence> evidence) {
        if (spec.analysis() == null) { EvidencePresentation.requireKind(spec, evidence); return; }
        var required = spec.analysis();
        var covered = EnumSet.noneOf(AnalysisContract.Source.class);
        for (Evidence item : evidence) {
            if (item.videoContent() != null) covered.add(VIDEO);
            if (item.comments() != null) covered.add(COMMENT);
            if (item.data() != null) covered.add(DANMAKU);
            if (item.analysis() != null) {
                if (item.analysis().sources() == null) covered.add(DANMAKU);
                else covered.addAll(item.analysis().sources().selectedSources());
            }
        }
        if (!required.sources().containsAll(covered)) throw new AgentException("TASK_CONSTRAINT_VIOLATION", "Answer cites an unselected source");
        if (!covered.containsAll(required.sources())) throw new AgentException("INVALID_EVIDENCE", "Answer lacks a selected analysis source");
    }
}
