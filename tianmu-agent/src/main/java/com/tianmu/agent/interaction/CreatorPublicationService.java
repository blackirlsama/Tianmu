package com.tianmu.agent.interaction;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;

@Service
public final class CreatorPublicationService {
    private final CreatorDraftStore drafts;
    private final ObjectProvider<CreatorPublicationPort> publications;
    public CreatorPublicationService(CreatorDraftStore drafts, ObjectProvider<CreatorPublicationPort> publications) {
        this.drafts = drafts; this.publications = publications;
    }
    public CreatorDraft owned(UUID runId, AccessScope scope) {
        CreatorDraft draft = drafts.get(runId);
        if (draft == null) return null;
        if (!draft.tenantId().equals(scope.tenantId()) || !draft.userId().equals(scope.userId())
                || !scope.videoIds().contains(Long.parseLong(draft.videoId())))
            throw new AgentException("FORBIDDEN", "只能操作本人视频的草稿。");
        return draft;
    }
    public CreatorDraft confirm(UUID runId, List<CreatorDraft.Item> selected, AccessScope scope) {
        CreatorDraft draft = owned(runId, scope);
        if (draft == null) throw new AgentException("DRAFT_NOT_FOUND", "草稿不存在。");
        CreatorDraft.validate(draft.kind(), selected);
        Set<String> known = new HashSet<>(); draft.items().forEach(item -> known.add(item.id()));
        if (selected.stream().anyMatch(item -> !known.contains(item.id()))) throw new AgentException("INVALID_DRAFT", "只能发送预览中的草稿。");
        CreatorPublicationPort port = publications.getIfAvailable();
        if (port == null && !draft.state().equals("PUBLISHED")) throw new AgentException("PUBLICATION_UNAVAILABLE", "当前环境尚未接入发送服务。");
        if (draft.state().equals("DRAFT")) {
            CreatorDraft frozen = draft.with("PUBLISHING", selected);
            if (!drafts.replace(draft, frozen)) throw new AgentException("DRAFT_CONFLICT", "草稿状态已更新，请刷新。");
            draft = frozen;
        } else if (!draft.items().equals(selected)) {
            throw new AgentException("DRAFT_CONFLICT", "发送已开始，重试必须使用已确认的原文和时间。");
        }
        if (draft.state().equals("PUBLISHED")) return draft;
        // If the response is lost, keep the exact frozen payload; business stable IDs make retry idempotent.
        try { port.publish(draft, scope); }
        catch (AgentException error) {
            // These responses prove no publication occurred (validation/authorization failed, batch transaction rolled back).
            // Unknown failures and timeouts must keep the payload frozen.
            if (Set.of("INVALID_DRAFT", "FORBIDDEN", "UNAUTHORIZED", "AUTH_CONTEXT_EXPIRED").contains(error.code()))
                drafts.replace(draft, draft.with("DRAFT", draft.items()));
            throw error;
        }
        CreatorDraft published = draft.with("PUBLISHED", draft.items());
        if (!drafts.replace(draft, published)) {
            CreatorDraft current = owned(runId, scope);
            if (current != null && current.equals(published)) return current;
            throw new AgentException("DRAFT_CONFLICT", "请刷新以确认发送结果。");
        }
        return published;
    }
}
