package com.tianmu.agent.tool;

import com.tianmu.agent.core.VideoContent;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.skill.SkillCatalog;
import org.springframework.stereotype.Component;
import java.time.DateTimeException;
import java.util.*;
import com.tianmu.agent.harness.hook.HookPoint;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.*;
import java.util.function.Function;
import com.tianmu.agent.harness.trace.TraceDetails;

/** Fixed admission boundary; tool implementations register independently of this dispatcher. */
@Component
public class ToolDispatcher {
    private final SkillCatalog skills;
    private final com.tianmu.agent.application.BusinessEvidencePolicy business = new com.tianmu.agent.application.BusinessEvidencePolicy();
    private final RuntimeHarness harness;
    private final ToolRegistry registry;
    private final com.tianmu.agent.application.ReportArtifacts artifacts;
    @org.springframework.beans.factory.annotation.Autowired
    public ToolDispatcher(SkillCatalog skills, RuntimeHarness harness, ToolRegistry registry, com.tianmu.agent.runtime.store.ReportStore reports) {
        this.skills = skills; this.harness = harness; this.registry = registry; this.artifacts = new com.tianmu.agent.application.ReportArtifacts(reports);
    }
    public ToolDispatcher(SkillCatalog skills, RuntimeHarness harness, ToolRegistry registry) { this(skills, harness, registry, null); }
    public List<ToolDefinition> definitions(Set<String> activeSkills) { return registry.definitions(activeSkills); }
    public ToolResult execute(ToolCall call, RunState run) {
        run.checkpoint();
        AgentTool tool = registry.get(call.name());
        if (run.task() != null && !run.task().allowedTools().contains(call.name()))
            throw new AgentException("TOOL_NOT_AVAILABLE", "Tool is outside the prepared capability ceiling");
        if (tool.requiresSkill()) skills.checkTool(call.name(), run.execution().activeSkills());
        try {
            var span = io.opentelemetry.api.trace.Span.current();
            com.tianmu.agent.harness.trace.TraceAttributes.apply(span, Map.of("tool.call_id", span.getSpanContext().getSpanId()));
            TraceDetails.capture("input", call, "TOOL_ARGUMENTS");
            ToolArguments.validate(call, registry.definition(call.name()));
            ToolResult result = tool.execute(call, new HostContext(run, tool));
            run.checkpoint();
            if (result == null || !call.name().equals(result.tool())) throw new AgentException("INVALID_TOOL_RESULT", "Tool result does not match the registered handler");
            TraceDetails.capture("output", result, "TOOL_RESULT");
            return result;
        } catch (IllegalArgumentException | DateTimeException | ArithmeticException e) {
            throw new AgentException("INVALID_ARGUMENTS", "Tool arguments are invalid");
        }
    }
    private final class HostContext implements ToolContext {
        private final RunState run; private final AgentTool tool;
        HostContext(RunState run, AgentTool tool) { this.run = run; this.tool = tool; }
        public UUID runId() { return run.id; }
        public AccessScope scope() { return run.owner; }
        public LocalDate businessDate() { return run.businessDate; }
        public void checkpoint() { run.checkpoint(); }
        public List<Map<String, String>> listSkills() { return skills.list(); }
        public Skill activateSkill(String name) {
            if (!tool.canActivateSkills()) throw new AgentException("TOOL_CAPABILITY_DENIED", "Tool cannot activate skills");
            checkSkillRevision(name);
            Skill skill = skills.read(name);
            return harness.stage(run, "agent.skill", HookPoint.PRE_SKILL, HookPoint.POST_SKILL,
                    Map.of("skill.name", name, "skill.version", skill.version()), () -> {
                        TraceDetails.input(Map.of("name", name, "version", skill.version()));
                        run.execution().activateSkill(name); return skill;
                    });
        }
        private void checkSkillRevision(String name) {
            Skill skill = skills.read(name);
            if (run.task() == null) return;
            var versions = run.task().resourceVersions();
            String version = versions.get("skill:" + name), fingerprint = versions.get("skill-content:" + name);
            if (version != null && !version.equals(skill.version())
                    || fingerprint != null && !fingerprint.equals(skills.fingerprint(name)))
                throw new AgentException("RESOURCE_VERSION_MISMATCH", "Skill differs from the prepared resource snapshot");
        }
        public List<Map<String, Object>> skillResources(String name) {
            checkSkillRevision(name);
            return skills.resources(name);
        }
        public Map<String, Object> skillResource(String name, String resource, int offset, int limit) {
            if (!tool.canActivateSkills() || !run.execution().activeSkills().contains(name))
                throw new AgentException("SKILL_REQUIRED", "Read the skill instructions before its references");
            checkSkillRevision(name);
            return skills.resource(name, resource, offset, limit);
        }
        public List<ToolDefinition> definitionsForSkill(String name) {
            Skill skill = skills.read(name);
            return registry.definitions(run.execution().activeSkills()).stream().filter(d -> skill.tools().contains(d.name()))
                    .filter(d -> run.task() == null || run.task().allowedTools().contains(d.name())).toList();
        }
        public Evidence evidence(String id) {
            Evidence value = run.execution().evidence().get(id);
            if (value == null || !value.owner().equals(run.owner) || !value.runId().equals(run.id))
                throw new AgentException("RESULT_NOT_FOUND", "Result is not accessible in this run");
            return value;
        }
        public void checkQuery(MetricQuery query) { business.checkQuery(run, query); }
        public com.tianmu.agent.core.DanmakuClassification reportClassification(String id){evidence(id);return run.execution().classification(id);}
        public void saveReportClassification(String id,com.tianmu.agent.core.DanmakuClassification progress){
            if(!tool.name().equals("create_danmaku_report"))throw new AgentException("TOOL_CAPABILITY_DENIED","Only report tool may update its draft");
            evidence(id);run.execution().classification(id,progress);
        }
        public Evidence registerEvidence(MetricData data) { return business.registerEvidence(run, data); }
        public <T> T query(Function<CallControl, T> operation) { return harness.toolCall(run, operation); }
        public <T> T videoQuery(Function<CallControl, T> operation) { return harness.videoToolCall(run, operation); }
        public void checkVideoQuery(long videoId) { business.checkVideoQuery(run, videoId); }
        public void checkVideoQuery(long videoId, Long startMs, Long endMs) { business.checkVideoQuery(run, videoId, startMs, endMs); }
        public Evidence registerVideoEvidence(com.tianmu.agent.core.VideoContent.Facts facts) { return business.registerVideoEvidence(run, facts); }
        public void checkDanmakuQuery(com.tianmu.agent.core.DanmakuTypes.Query query) { business.checkDanmakuQuery(run, query); }
        public Evidence registerDanmakuEvidence(com.tianmu.agent.core.DanmakuTypes.Dataset data) { return business.registerDanmakuEvidence(run, data); }
        public com.tianmu.agent.core.DanmakuTypes.ReportRef saveReport(String evidenceId, byte[] html) { return artifacts.save(run, evidenceId, html); }
    }
}
