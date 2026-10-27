package com.tianmu.agent.tool.builtin;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.query.AudienceQueryPort;
import com.tianmu.agent.tool.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public final class QueryAudienceTool implements AgentTool {
    private final ObjectProvider<AudienceQueryPort> ports;
    public QueryAudienceTool(ObjectProvider<AudienceQueryPort> ports){this.ports=ports;}
    public String name(){return "query_audience";}
    public ToolResult execute(ToolCall call,ToolContext context){
        var query=new DanmakuTypes.Query(ToolArguments.integer(call,"video_id"),LocalDateTime.parse(ToolArguments.text(call,"start")),LocalDateTime.parse(ToolArguments.text(call,"end")));
        context.checkDanmakuQuery(query);var port=ports.getIfAvailable();
        if(port==null)throw new AgentException("AUDIENCE_UNAVAILABLE","当前环境未接入联合观众反馈。");
        var data=context.query(control->port.audience(query,context.scope(),control));
        if(data==null || data.sources()==null || !query.equals(data.query()) || data.dataAsOf().isAfter(Instant.now().plusSeconds(5))
                || data.audience().corpusMessages()!=data.totalMessages() || data.audience().corpus().stream().anyMatch(AudienceData.TextGroup::truncated))
            throw new AgentException("INVALID_EVIDENCE","Incomplete or inconsistent audience snapshot");
        var evidence=context.registerDanmakuEvidence(data);
        var counts=new LinkedHashMap<String,Object>();counts.put("danmaku",data.sources().danmaku());counts.put("comments",data.sources().comments());
        counts.put("author_danmaku",data.sources().danmakuAuthors());counts.put("author_comments",data.sources().commentAuthors());
        counts.put("danmaku_viewers",data.sources().danmakuViewers());counts.put("comment_viewers",data.sources().commentViewers());
        counts.put("unique_viewers",data.sources().uniqueViewers());
        counts.put("context_comments",data.sources().contextComments());
        return new ToolResult(name(),Map.of("evidence_id",evidence.id(),"counts",counts,"classification",context.reportClassification(evidence.id()).view(data.audience())),evidence.id());
    }
}
