package com.tianmu.agent.runtime.store;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.MemoryTypes;
import com.tianmu.agent.runtime.RunState;
import java.util.*;

public interface RunRepository extends ResourceScopeStore {
    record Created(RunState run, boolean fresh) {}
    default Created create(String message, AccessScope owner, UUID sessionId, String idempotencyKey, UUID replyToRunId) {
        return create(message, owner, sessionId, idempotencyKey, replyToRunId, null);
    }
    Created create(String message, AccessScope owner, UUID sessionId, String idempotencyKey, UUID replyToRunId, com.tianmu.agent.core.RequestContext context);
    RunState load(UUID id, AccessScope caller);
    default List<RunState> recoverQueued() { return List.of(); }
    default void refresh(RunState run) {}
    default boolean durable() { return false; }
    /** Read-only readiness check; durable adapters should verify their authority to accept writes. */
    default void verifyReady() {}
    /** Owner-only metadata used to authorize requested resources before loading their contents. */
    default Set<Long> resourcesForRun(UUID id, AccessScope caller) { return load(id, caller).owner.videoIds(); }
}
