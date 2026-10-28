package com.tianmu.agent.access;

import com.tianmu.agent.core.AgentTypes.AccessScope;

/** Replace the demo credential with a verified identity from the team's authentication system. */
public interface IdentityPort {
    AccessScope authenticate(String credential);
    default AccessScope forCreate(String credential, String message, java.util.UUID replyToRunId) { return authenticate(credential); }
    default AccessScope forCreate(String credential, String message, java.util.UUID replyToRunId,
                                 com.tianmu.agent.core.RequestContext page, java.util.UUID sessionId) {
        AccessScope scope = forCreate(credential, message, replyToRunId);
        if (page != null && !scope.videoIds().contains(Long.parseLong(page.videoId())))
            throw new com.tianmu.agent.core.AgentException("FORBIDDEN", "Current video outside scope");
        return scope;
    }
    default AccessScope forRun(String credential, java.util.UUID runId) { return authenticate(credential); }
    default AccessScope forSession(String credential, java.util.UUID sessionId, long before) { return authenticate(credential); }
}
