package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.TaskTypes.PreparedTask;
import com.tianmu.agent.runtime.store.EvidenceStore;
import java.time.LocalDate;
import java.util.UUID;

/** Evidence policies can inspect the frozen task and admit evidence, but cannot terminate a run. */
public interface EvidenceContext {
    UUID runId();
    AccessScope scope();
    LocalDate businessDate();
    PreparedTask task();
    EvidenceStore evidenceStore();
    void checkpoint();
}
