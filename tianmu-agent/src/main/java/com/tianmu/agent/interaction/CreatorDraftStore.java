package com.tianmu.agent.interaction;

import java.util.UUID;

public interface CreatorDraftStore {
    void create(CreatorDraft draft);
    CreatorDraft get(UUID runId);
    boolean replace(CreatorDraft expected, CreatorDraft next);
}
