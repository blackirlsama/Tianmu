package com.tianmu.agent.core;

/** Stored through the existing private evidence store, independently of rendered HTML. */
public record ReportCheckpoint(String kind,String evidenceId,DanmakuClassification classification) {
    public ReportCheckpoint {
        if(!"audience-report-checkpoint-v1".equals(kind) || evidenceId==null || !evidenceId.matches("ev_[A-Za-z0-9_-]{1,80}") || classification==null)
            throw new IllegalArgumentException("Invalid report checkpoint");
    }
}
