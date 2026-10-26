package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.store.ReportStore;
import java.util.Map;

public final class ReportArtifacts {
    private final ReportStore reports;
    private final BusinessEvidencePolicy policy = new BusinessEvidencePolicy();
    public ReportArtifacts(ReportStore reports) { this.reports = reports; }
    public DanmakuTypes.ReportRef save(RunState run, String evidenceId, byte[] html) {
        var value = run.execution().evidence().get(evidenceId);
        if (value == null || !value.owner().equals(run.owner) || !value.runId().equals(run.id))
            throw new AgentException("RESULT_NOT_FOUND", "Result is not accessible in this run");
        if (value.analysis() == null) throw new AgentException("INVALID_EVIDENCE", "A danmaku analysis snapshot is required");
        policy.checkDanmakuQuery(run, value.analysis().query());
        if (reports == null) throw new AgentException("STORAGE_REQUIRED", "Report storage unavailable");
        var ref = reports.saveReport(run.id, run.owner, evidenceId, html);
        run.checkpoint(); run.emit("ARTIFACT_READY", Map.of("artifact_id", ref.artifactId(), "url", ref.url(), "media_type", "text/html"));
        return ref;
    }
}
