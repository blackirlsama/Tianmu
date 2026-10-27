package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.query.CommentQueryPort;
import com.tianmu.agent.tool.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public final class QueryVideoCommentsTool implements AgentTool {
    private final ObjectProvider<CommentQueryPort> queries;
    public QueryVideoCommentsTool(ObjectProvider<CommentQueryPort> queries) { this.queries = queries; }
    public String name() { return "query_video_comments"; }
    public ToolResult execute(ToolCall call, ToolContext context) {
        var query = new DanmakuTypes.Query(ToolArguments.integer(call, "video_id"), LocalDateTime.parse(ToolArguments.text(call, "start")), LocalDateTime.parse(ToolArguments.text(call, "end")));
        context.checkCommentQuery(query);
        int offset = call.arguments().containsKey("offset") ? Math.toIntExact(ToolArguments.integer(call, "offset")) : 0;
        Evidence evidence;
        if (call.arguments().containsKey("evidence_id")) {
            evidence = context.evidence(ToolArguments.text(call, "evidence_id"));
            if (evidence.comments() == null || !query.equals(evidence.comments().query())) throw new AgentException("INVALID_EVIDENCE", "Comment page query changed");
        } else {
            if (offset != 0) throw new AgentException("INVALID_COMMENT_OFFSET", "Start at offset zero");
            CommentQueryPort port = queries.getIfAvailable();
            if (port == null) throw new AgentException("COMMENTS_UNAVAILABLE", "当前环境未接入评论数据。");
            var data = context.query(control -> port.comments(query, context.scope(), control));
            if (data == null || !query.equals(data.query()) || data.dataAsOf().isAfter(Instant.now().plusSeconds(5)))
                throw new AgentException("INVALID_EVIDENCE", "Comment query response mismatch");
            evidence = context.registerCommentEvidence(data);
        }
        if (offset != context.commentOffset(evidence.id())) throw new AgentException("INVALID_COMMENT_OFFSET", "Continue from next_offset; do not skip pages");
        var data = evidence.comments(); var page = data.page(offset);
        context.advanceComments(evidence.id(), offset, (Integer) page.get("next_offset"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("evidence_id", evidence.id()); result.put("query", data.query());
        result.put("total_comments", data.totalComments()); result.put("audience_comments", data.audienceComments());
        result.put("author_comments", data.totalComments() - data.audienceComments()); result.put("unique_audience", data.uniqueAudience());
        result.put("page", page);
        return new ToolResult(name(), result, evidence.id());
    }
}
