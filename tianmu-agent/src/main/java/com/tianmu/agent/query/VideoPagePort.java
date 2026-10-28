package com.tianmu.agent.query;

import com.tianmu.agent.core.AgentTypes.AccessScope;

/** Small, authenticated page metadata. Does not imply that video content has been analyzed. */
public interface VideoPagePort {
    record Page(String videoId, String title, long durationMs) {}
    Page page(long videoId, AccessScope scope);
}
