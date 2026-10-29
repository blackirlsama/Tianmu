package com.tianmu.agent.tool.builtin;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tianmu.agent.application.AudienceTopics;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.tool.*;
import java.util.*;

/** Validates model-supplied batch membership. No model calls or hidden orchestration. */
final class ReportDraftEditor {
    private final ObjectMapper json;
    private final com.tianmu.agent.application.ReportAnalysisCache cache;
    ReportDraftEditor(ObjectMapper json,com.tianmu.agent.application.ReportAnalysisCache cache){this.cache=cache;this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
    void complete(ToolContext context,String id) {
        var data=context.evidence(id).analysis();var progress=context.reportClassification(id);
        if(data.sources()==null || progress.merged() || progress.offset()!=data.audience().corpus().size())return;
        Set<String> members=new HashSet<>();
        for(Topic topic:progress.topics())for(String member:topic.sampleIds())
            if(!members.add(member))throw new AgentException("INVALID_REPORT","Duplicate topic membership");
        if(!members.equals(data.audience().corpus().stream().map(TextGroup::id).collect(java.util.stream.Collectors.toSet())))
            throw new AgentException("INVALID_REPORT","Incomplete topic membership");
        var completed=new DanmakuClassification(progress.offset(),progress.topics(),progress.videoEvidenceId(),true);
        context.saveReportClassification(id,completed);
        VideoContent.Facts video=progress.videoEvidenceId()==null?null:context.evidence(progress.videoEvidenceId()).videoContent();
        cache.put(context.scope(),data,video,completed,context.reportCacheVariant());
    }
    ToolResult append(ToolCall call,ToolContext context){
        String id=ToolArguments.text(call,"evidence_id");var evidence=context.evidence(id);var data=evidence.analysis();
        if(data==null || data.audience()==null)throw new AgentException("INVALID_EVIDENCE","Full danmaku evidence required");
        context.checkDanmakuQuery(data.query());
        var progress=Objects.requireNonNullElse(context.reportClassification(id),DanmakuClassification.empty());long offset=ToolArguments.integer(call,"offset");
        if(offset!=progress.offset())throw new AgentException("INVALID_REPORT","Use the returned current offset; do not skip or repeat batches");
        List<TextGroup> batch=DanmakuClassification.batch(data.audience(),progress.offset());
        if(batch.isEmpty())throw new AgentException("INVALID_REPORT","All messages already classified; create the report");
        VideoContent.Facts video=null;
        String videoId=call.arguments().containsKey("video_evidence_id")?ToolArguments.text(call,"video_evidence_id"):progress.videoEvidenceId();
        if(progress.videoEvidenceId()!=null && !progress.videoEvidenceId().equals(videoId))throw new AgentException("INVALID_REPORT","同一份报告必须复用同一份视频证据。");
        if(videoId!=null){
            video=context.evidence(videoId).videoContent();
            if(video==null || video.videoId()!=data.query().videoId())throw new AgentException("INVALID_EVIDENCE","Video must match");
        }
        var cached=cache.get(context.scope(),data,video,context.reportCacheVariant());
        if(cached==null || !cached.merged())cache.claim(context.scope(),data,video,context.reportCacheVariant(),context.runId());
        if(cached!=null && (cached.offset()>progress.offset() || cached.offset()==progress.offset() && cached.merged() && !progress.merged())){
            var restored=new DanmakuClassification(cached.offset(),cached.topics(),videoId,cached.merged());
            context.saveReportClassification(id,restored);
            checkBudget(context,DanmakuClassification.remainingBatches(data.audience(),restored.offset()));
            com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("report.cache_hit",true,"report.reused_groups",restored.offset()));
            return new ToolResult("create_danmaku_report",Map.of("evidence_id",id,"classification",restored.view(data.audience()),"reused_groups",restored.offset()),id);
        }
        checkBudget(context,Math.max(0,DanmakuClassification.remainingBatches(data.audience(),progress.offset())-1));
        List<String> keys=new ArrayList<>();JsonNode nodes;
        try{
            nodes=json.readTree(ToolArguments.text(call,"topics_json"));
            if(nodes.isObject() && nodes.size()==1 && nodes.has("topics"))nodes=nodes.get("topics");
            if(!nodes.isArray())throw new IllegalArgumentException();
            Map<String,String> aliases=new LinkedHashMap<>();Set<String> canonical=new HashSet<>(),assigned=new HashSet<>();
            for(int i=0;i<batch.size();i++){aliases.put("G"+(progress.offset()+i+1),batch.get(i).id());canonical.add(batch.get(i).id());}
            Map<String,List<String>> compressed=new HashMap<>();
            for(var input:DanmakuClassification.inputs(data.audience(),progress.offset()))
                compressed.put(input.id(),input.members().stream().map(TextGroup::id).toList());
            for(int n=0;n<nodes.size();n++){
                JsonNode node=nodes.get(n);
                JsonNode refs=node.path("sample_ids");
                if(!refs.isArray())throw new IllegalArgumentException();
                if(refs.isEmpty()){((com.fasterxml.jackson.databind.node.ArrayNode)nodes).remove(n--);continue;}
                String key=node.path("topic_key").asText();
                boolean fallback=key.equals("OTHER") && node.path("title").asText().equals("其他讨论");
                if(!(fallback || key.matches(data.sources()==null?"T[1-6]":"T(?:[1-9]|[1-9][0-9]|100)")) || keys.contains(key))throw new IllegalArgumentException();
                var expanded=json.createArrayNode();
                for(JsonNode ref:refs){
                    if(!ref.isTextual())throw new IllegalArgumentException();String supplied=ref.textValue();
                    List<String> members=compressed.get(supplied);
                    if(members==null){String member=aliases.get(supplied);if(member==null && canonical.contains(supplied))member=supplied;
                        if(member==null)throw new AgentException("INVALID_REPORT","Unknown batch ID: "+supplied+". Use only IDs in classification.groups.");
                        members=List.of(member);
                    }
                    for(String member:members)if(assigned.add(member))expanded.add(member);
                }
                ((ObjectNode)node).set("sample_ids",expanded);refs=expanded;
                if(refs.isEmpty()){((com.fasterxml.jackson.databind.node.ArrayNode)nodes).remove(n--);continue;}
                keys.add(key);((ObjectNode)node).remove("topic_key");
            }
            List<String> missing=aliases.entrySet().stream().filter(e->!assigned.contains(e.getValue())).map(Map.Entry::getKey).toList();
            if(!missing.isEmpty()){
                ObjectNode other=null;
                for(JsonNode node:nodes)if(node.path("title").asText().equals("其他讨论")){other=(ObjectNode)node;break;}
                if(other==null){
                    other=json.createObjectNode();other.put("title","其他讨论");
                    other.put("observation","本批次中未明确归入其他主题的零散互动，具体原话见下方。");other.put("recommendation","");other.set("sample_ids",json.createArrayNode());
                    ((com.fasterxml.jackson.databind.node.ArrayNode)nodes).add(other);keys.add("OTHER");
                }
                var otherIds=(com.fasterxml.jackson.databind.node.ArrayNode)other.get("sample_ids");
                for(String alias:missing){String member=aliases.get(alias);otherIds.add(member);assigned.add(member);}
            }
        }catch(AgentException e){throw e;}
        catch(Exception e){throw new AgentException("INVALID_REPORT","topics_json must be an array or {\"topics\":[...]}; use stable topic_key values (legacy T1..T6; joint report T1..T100) and assign every current batch ID exactly once.");}
        long total=batch.stream().mapToLong(TextGroup::count).sum();
        Overview subset=new Overview(List.of(),List.of(),total,batch,total,total);
        List<Topic> parsed=AudienceTopics.parse(json,nodes.toString(),subset,video,batch.size(),batch.size());
        if(video!=null && parsed.stream().noneMatch(t->!t.videoContext().isBlank()) && progress.topics().stream().noneMatch(t->!t.videoContext().isBlank()))
            throw new AgentException("INVALID_REPORT","Add video_context and actual sampled video_at_ms to at least one relevant topic. Explain the visible relationship, or what the video cannot confirm; do not invent visual facts. Resubmit this full batch.");
        if(parsed.stream().mapToInt(t->t.sampleIds().size()).sum()!=batch.size())throw new AgentException("INVALID_REPORT","Every ID in the current batch must be assigned exactly once; sample_ids are all members, not examples");
        Map<String,Topic> merged=new LinkedHashMap<>();progress.topics().forEach(t->merged.put(t.id(),t));
        for(int i=0;i<parsed.size();i++){
            Topic t=parsed.get(i),old=merged.get(keys.get(i));List<String> members=new ArrayList<>();if(old!=null)members.addAll(old.sampleIds());members.addAll(t.sampleIds());
            merged.put(keys.get(i),new Topic(keys.get(i),t.title(),t.observation(),t.recommendation(),members,Math.addExact(t.messages(),old==null?0:old.messages()),
                    join(old==null?"":old.videoContext(),t.videoContext()),
                    java.util.stream.Stream.concat(old==null?java.util.stream.Stream.<Long>empty():old.videoAtMs().stream(),t.videoAtMs().stream()).distinct().sorted().toList()));
        }
        var saved=new DanmakuClassification(progress.offset()+batch.size(),List.copyOf(merged.values()),videoId);context.saveReportClassification(id,saved);
        cache.put(context.scope(),data,video,saved,context.reportCacheVariant());
        return new ToolResult("create_danmaku_report",Map.of("evidence_id",id,"classification",saved.view(data.audience())),id);
    }
    private static String join(String previous,String next){
        if(previous.isBlank())return next;if(next.isBlank() || previous.equals(next))return previous;
        return previous+"\n"+next;
    }
    private void checkBudget(ToolContext context,int needed){
        Integer available=context.remainingModelCalls();
        if(available!=null && available<needed)throw new AgentException("REPORT_BUDGET_INSUFFICIENT","Remaining report batches need at least "+needed+" model calls; only "+available+" remain. Stop without retrying or publishing a partial report.");
    }
}
