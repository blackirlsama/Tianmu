package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.interaction.*;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public final class PrepareCreatorInteractionsTool implements AgentTool {
    private final CreatorDraftStore drafts;
    public PrepareCreatorInteractionsTool(CreatorDraftStore drafts) { this.drafts = drafts; }
    public String name() { return "prepare_creator_interactions"; }
    public ToolResult execute(ToolCall call, ToolContext context) {
        long videoId = ToolArguments.integer(call, "video_id");
        Evidence evidence = context.evidence(ToolArguments.text(call, "video_evidence_id"));
        if (evidence.videoContent() == null || evidence.videoContent().videoId() != videoId)
            throw new AgentException("INVALID_EVIDENCE", "请先读取当前视频内容。");
        context.checkVideoQuery(videoId, evidence.videoContent().startMs(), evidence.videoContent().endMs());
        String kind = ToolArguments.text(call, "kind");
        context.checkCreatorDraft(videoId, kind);
        List<CreatorDraft.Item> items = new ArrayList<>();
        for (Object raw : (List<?>)call.arguments().get("items")) {
            Map<?, ?> item = (Map<?, ?>)raw;
            Long at = item.containsKey("playback_ms") ? new java.math.BigDecimal(item.get("playback_ms").toString()).longValueExact() : null;
            if (at != null && (at < evidence.videoContent().startMs() || at >= evidence.videoContent().endMs()))
                throw new AgentException("INVALID_DRAFT", "弹幕时间必须位于已分析的视频片段内。");
            long id;
            do { id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE; } while (id == 0);
            items.add(new CreatorDraft.Item(Long.toString(id), (String)item.get("text"), at));
        }
        CreatorDraft.validate(kind, items);
        var scope = context.scope();
        CreatorDraft draft = new CreatorDraft(context.runId(), scope.tenantId(), scope.userId(), Long.toString(videoId), kind, "DRAFT", items);
        context.checkpoint(); drafts.create(draft);
        return new ToolResult(name(), Map.of("draft_ready", true, "count", items.size(), "kind", kind), evidence.id(),
                new Answer("已生成" + items.size() + "条" + (kind.equals("COMMENT") ? "评论" : "弹幕") + "草稿。请在下方预览、修改并勾选，确认后才会用你的作者账号发送。", List.of(evidence.id())));
    }
}
