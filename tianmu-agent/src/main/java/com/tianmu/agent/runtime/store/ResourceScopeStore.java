package com.tianmu.agent.runtime.store;

import com.tianmu.agent.core.AgentTypes.AccessScope;
import java.util.Set;
import java.util.UUID;

/** Metadata needed to reauthorize stored resources, without exposing message or report contents. */
public interface ResourceScopeStore {
    Set<Long> resourcesForRun(UUID id, AccessScope caller);
    default Set<Long> resourcesForSession(UUID id, AccessScope caller, long before) { return Set.of(); }
}
