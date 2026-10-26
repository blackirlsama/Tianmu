package com.tianmu.agent.tool.builtin;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.query.DanmakuQueryPort;
import com.tianmu.agent.tool.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

@Component
public final class QueryDanmakuTool implements AgentTool {
    private final DanmakuQueryPort queries;
    public QueryDanmakuTool(DanmakuQueryPort queries) { this.queries = queries; }
    public String name() { return "query_danmaku"; }
    public ToolResult execute(ToolCall call, ToolContext context) {
        Query query = new Query(ToolArguments.integer(call, "video_id"), LocalDateTime.parse(ToolArguments.text(call, "start")), LocalDateTime.parse(ToolArguments.text(call, "end")));
        context.checkDanmakuQuery(query);
        Dataset raw = context.query(control -> queries.query(query, context.scope(), control));
        if (raw == null || !raw.query().equals(query) || raw.dataAsOf().isAfter(Instant.now().plusSeconds(5)))
            throw new AgentException("INVALID_EVIDENCE", "Analysis response does not match the query");
        Map<String, List<Sample>> grouped = new LinkedHashMap<>();
        for (Sample sample : raw.samples()) {
            String safeText = redact(sample.text());
            grouped.computeIfAbsent(safeText, ignored -> new ArrayList<>()).add(sample);
        }
        List<Sample> samples = grouped.entrySet().stream().map(entry -> {
            List<Sample> matches = entry.getValue();
            Sample first = matches.get(0);
            List<SampleOccurrence> occurrences = matches.stream()
                    .flatMap(s -> s.occurrences().stream()).toList();
            return new Sample(first.id(), first.bucket(), entry.getKey(), matches.stream().anyMatch(Sample::truncated), occurrences);
        }).toList();
        Dataset data = new Dataset(query, raw.totalMessages(), raw.uniqueSenders(), raw.buckets(), samples, raw.dataAsOf(), raw.source(), raw.demo(), raw.audience());
        Evidence evidence = context.registerDanmakuEvidence(data);
        Dataset visible=data;
        if(data.audience()!=null){
            var a=data.audience();
            var redacted=new AudienceData.Overview(List.of(),a.playback(),a.unknownPlayback(),List.of(),0,a.totalMessages());
            visible=new Dataset(query,data.totalMessages(),data.uniqueSenders(),data.buckets(),List.of(),data.dataAsOf(),data.source(),data.demo(),redacted);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("analysis",visible);result.put("evidence_id",evidence.id());
        if(data.audience()==null)result.put("sampling_method",data.samplingMethod());
        else {
            if(data.audience().corpusMessages()!=data.totalMessages() || data.audience().corpus().stream().anyMatch(AudienceData.TextGroup::truncated))
                throw new AgentException("FULL_ANALYSIS_INCOMPLETE","未读取全部弹幕，无法生成全量报告，请缩小时间范围后重试。");
            result.put("classification",Objects.requireNonNullElse(context.reportClassification(evidence.id()),DanmakuClassification.empty()).view(data.audience()));
        }
        return new ToolResult(name(),result,evidence.id());
    }
    private static String redact(String text) { return text.replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}", "[email]").replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[phone]"); }
}
