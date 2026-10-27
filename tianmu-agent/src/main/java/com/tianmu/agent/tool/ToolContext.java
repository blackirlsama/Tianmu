package com.tianmu.agent.tool;

import com.tianmu.agent.core.VideoContent;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;

/** Trusted host capabilities, without mutable RunState, terminal control, SSE, registry or Harness access. */
public interface ToolContext {
    UUID runId();
    AccessScope scope();
    LocalDate businessDate();
    void checkpoint();
    List<Map<String, String>> listSkills();
    Skill activateSkill(String name);
    default List<Map<String, Object>> skillResources(String name) { return List.of(); }
    default Map<String, Object> skillResource(String name, String resource, int offset, int limit) {
        throw new com.tianmu.agent.core.AgentException("SKILL_RESOURCE_NOT_FOUND", "Skill references are unavailable");
    }
    List<ToolDefinition> definitionsForSkill(String name);
    Evidence evidence(String id);
    default boolean reportRequiresVideo(){return true;}
    default String reportCacheVariant(){return "";}
    default Integer remainingModelCalls(){return null;}
    default void checkCommentQuery(com.tianmu.agent.core.DanmakuTypes.Query query) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Comment query unavailable"); }
    default Evidence registerCommentEvidence(com.tianmu.agent.core.VideoComments.Dataset data) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Comment evidence unavailable"); }
    default int commentOffset(String id) { return 0; }
    default void advanceComments(String id, int expected, int next) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Comment progress unavailable"); }
    default com.tianmu.agent.core.DanmakuClassification reportClassification(String evidenceId){return com.tianmu.agent.core.DanmakuClassification.empty();}
    default void saveReportClassification(String evidenceId,com.tianmu.agent.core.DanmakuClassification progress){throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED","Classification storage unavailable");}
    Evidence registerEvidence(MetricData data);
    default void checkDanmakuQuery(com.tianmu.agent.core.DanmakuTypes.Query query) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Danmaku analysis unavailable"); }
    default Evidence registerDanmakuEvidence(com.tianmu.agent.core.DanmakuTypes.Dataset data) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Danmaku evidence unavailable"); }
    default com.tianmu.agent.core.DanmakuTypes.ReportRef saveReport(String evidenceId, byte[] html) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Report storage unavailable"); }
    default void checkVideoQuery(long videoId) {
        checkpoint();
        if (!scope().videoIds().contains(videoId)) throw new com.tianmu.agent.core.AgentException("FORBIDDEN", "Video outside scope");
    }
    default void checkVideoQuery(long videoId, Long startMs, Long endMs) { checkVideoQuery(videoId); }
    default Evidence registerVideoEvidence(com.tianmu.agent.core.VideoContent.Facts facts) { throw new com.tianmu.agent.core.AgentException("TOOL_CAPABILITY_DENIED", "Video evidence unavailable"); }
    default <T> T videoQuery(Function<CallControl, T> operation) { return query(operation); }
    void checkQuery(MetricQuery query);
    <T> T query(Function<CallControl, T> operation);
}
