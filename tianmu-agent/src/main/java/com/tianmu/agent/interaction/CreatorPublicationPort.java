package com.tianmu.agent.interaction;

import com.tianmu.agent.core.AgentTypes.AccessScope;

/** Called only by the explicit confirmation endpoint, never exposed to Harness. */
public interface CreatorPublicationPort {
    void publish(CreatorDraft draft, AccessScope scope);
}
