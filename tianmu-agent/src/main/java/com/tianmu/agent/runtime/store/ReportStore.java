package com.tianmu.agent.runtime.store;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.DanmakuTypes.ReportRef;
import java.util.UUID;

/** Report consumers need neither run creation nor runner recovery capabilities. */
public interface ReportStore {
    default ReportRef saveReport(UUID runId, AccessScope owner, String evidenceId, byte[] html) {
        throw new AgentException("STORAGE_REQUIRED", "Report storage unavailable");
    }
    default byte[] report(UUID runId, AccessScope owner, String artifactId) {
        throw new AgentException("RESULT_NOT_FOUND", "Report not found");
    }
}
