package com.tianmu.agent.interaction;

import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;

@Component
@ConditionalOnProperty(name="agent.storage.mode", havingValue="memory", matchIfMissing=true)
public final class MemoryCreatorDraftStore implements CreatorDraftStore {
    private final Map<UUID, CreatorDraft> entries = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(2000).expireAfterAccess(java.time.Duration.ofDays(7)).<UUID,CreatorDraft>build().asMap();
    public void create(CreatorDraft draft) {
        if (entries.putIfAbsent(draft.runId(), draft) != null) throw new com.tianmu.agent.core.AgentException("DRAFT_CONFLICT", "该任务已有草稿。");
    }
    public CreatorDraft get(UUID id) { return entries.get(id); }
    public boolean replace(CreatorDraft expected, CreatorDraft next) { return entries.replace(expected.runId(), expected, next); }
}
