package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentTypes.Snapshot;

/** Best-effort derived consumers run only after the terminal database commit; they cannot change its outcome. */
public interface RunCompletionListener {
    void completed(RunState run,Snapshot snapshot);
}
