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
    void merge(ToolCall call,ToolContext context) {
        String id=ToolArguments.text(call,"evidence_id");var data=context.evidence(id).analysis();var progress=context.reportClassification(id);
        if(data==null || data.sources()==null || progress.offset()!=data.audience().corpus().size() || progress.merged())
            throw new AgentException("INVALID_REPORT","Complete every source batch before merging its topics");
        context.checkDanmakuQuery(data.query());
        VideoContent.Facts video=progress.videoEvidenceId()==null?null:context.evidence(progress.videoEvidenceId()).videoContent();
        cache.claim(context.scope(),data,video,context.reportCacheVariant(),context.runId());
        try {
            JsonNode nodes=json.readTree(ToolArguments.text(call,"merge_json"));
            if(nodes.isObject() && nodes.size()==1 && nodes.has("topics"))nodes=nodes.get("topics");
            if(!nodes.isArray() || nodes.isEmpty() || nodes.size()>100)throw new IllegalArgumentException();
            Map<String,Topic> topics=new HashMap<>();Map<String,String> aliases=new HashMap<>();List<TextGroup> groups=new ArrayList<>();
            for(Topic topic:progress.topics()) {
                String alias=Integer.toString(groups.size()+1);topics.put(alias,topic);aliases.put(topic.id(),alias);
                groups.add(new TextGroup(alias,topic.title(),topic.messages(),null,false));
            }
            Set<String> assigned=new HashSet<>();
            for(JsonNode node:nodes) {
                if(!(node instanceof ObjectNode object) || !node.path("topic_keys").isArray() || node.has("sample_ids"))throw new IllegalArgumentException();
                var members=json.createArrayNode();
                for(JsonNode key:node.get("topic_keys")) {
                    String alias=key.isTextual()?aliases.get(key.asText()):null;
                    if(alias==null || !assigned.add(alias))throw new IllegalArgumentException();members.add(alias);
                }
                object.remove("topic_keys");object.set("sample_ids",members);
            }
            if(!assigned.equals(topics.keySet()))throw new IllegalArgumentException();
            Overview overview=new Overview(List.of(),List.of(),data.totalMessages(),groups,data.totalMessages(),data.totalMessages());
            List<Topic> merged=new ArrayList<>();
            for(Topic topic:AudienceTopics.parse(json,nodes.toString(),overview,video,100,100)) {
                List<String> members=topic.sampleIds().stream().flatMap(alias->topics.get(alias).sampleIds().stream()).toList();
                merged.add(new Topic(topic.id(),topic.title(),topic.observation(),topic.recommendation(),members,topic.messages(),topic.videoContext(),topic.videoAtMs()));
            }
            if(video!=null && merged.stream().noneMatch(t->!t.videoContext().isBlank()))throw new IllegalArgumentException();
            var completed=new DanmakuClassification(progress.offset(),merged,progress.videoEvidenceId(),true);
            context.saveReportClassification(id,completed);cache.put(context.scope(),data,video,completed,context.reportCacheVariant());
        }catch(AgentException e){throw e;}
        catch(Exception e){throw new AgentException("INVALID_REPORT","merge_json must be an array or {\"topics\":[...]}, covering every existing topic_key exactly once. Each item has title, observation, recommendation, topic_keys and optional anchored video_context/video_at_ms. Preserve distinct opinions; never drop long-tail topics.");}
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
            checkBudget(context,DanmakuClassification.remainingBatches(data.audience(),restored.offset())+(data.sources()!=null && !restored.merged()?1:0));
            com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("report.cache_hit",true,"report.reused_groups",restored.offset()));
            return new ToolResult("create_danmaku_report",Map.of("evidence_id",id,"classification",restored.view(data.audience()),"reused_groups",restored.offset()),id);
        }
        checkBudget(context,Math.max(0,DanmakuClassification.remainingBatches(data.audience(),progress.offset())-1)+(data.sources()!=null?1:0));
        List<String> keys=new ArrayList<>();JsonNode nodes;
        try{
            nodes=json.readTree(ToolArguments.text(call,"topics_json"));
            if(nodes.isObject() && nodes.size()==1 && nodes.has("topics"))nodes=nodes.get("topics");
            if(!nodes.isArray())throw new IllegalArgumentException();
            Map<String,String> aliases=new LinkedHashMap<>();Set<String> canonical=new HashSet<>(),assigned=new HashSet<>();
            for(int i=0;i<batch.size();i++){aliases.put("G"+(progress.offset()+i+1),batch.get(i).id());canonical.add(batch.get(i).id());}
            for(int n=0;n<nodes.size();n++){
                JsonNode node=nodes.get(n);
                JsonNode refs=node.path("sample_ids");
                if(!refs.isArray())throw new IllegalArgumentException();
                if(refs.isEmpty()){((com.fasterxml.jackson.databind.node.ArrayNode)nodes).remove(n--);continue;}
                String key=node.path("topic_key").asText();if(!key.matches(data.sources()==null?"T[1-6]":"T(?:[1-9]|[1-9][0-9]|100)") || keys.contains(key))throw new IllegalArgumentException();
                for(int i=0;i<refs.size();i++){
                    if(!refs.get(i).isTextual())throw new IllegalArgumentException();String supplied=refs.get(i).textValue();
                    String member=aliases.get(supplied);if(member==null && canonical.contains(supplied))member=supplied;
                    if(member==null)throw new AgentException("INVALID_REPORT","Unknown batch ID: "+supplied+". Use only IDs in the current classification.groups.");
                    if(!assigned.add(member)){((com.fasterxml.jackson.databind.node.ArrayNode)refs).remove(i--);continue;}
                    ((com.fasterxml.jackson.databind.node.ArrayNode)refs).set(i,com.fasterxml.jackson.databind.node.TextNode.valueOf(member));
                }
                if(refs.isEmpty()){((com.fasterxml.jackson.databind.node.ArrayNode)nodes).remove(n--);continue;}
                keys.add(key);((ObjectNode)node).remove("topic_key");
            }
            List<String> missing=aliases.entrySet().stream().filter(e->!assigned.contains(e.getValue())).map(Map.Entry::getKey).toList();
            if(!missing.isEmpty()){
                ObjectNode other=null;
                for(JsonNode node:nodes)if(node.path("title").asText().equals("其他讨论")){other=(ObjectNode)node;break;}
                if(other==null){
                    String fallbackKey=null;for(int i=1;i<=100;i++)if(!keys.contains("T"+i)){fallbackKey="T"+i;break;}
                    if(fallbackKey==null)throw new AgentException("INVALID_REPORT","No topic key remains for uncategorized records");
                    other=json.createObjectNode();other.put("title","其他讨论");
                    other.put("observation","本批次中未明确归入其他主题的零散互动，具体原话见下方。");other.put("recommendation","");other.set("sample_ids",json.createArrayNode());
                    ((com.fasterxml.jackson.databind.node.ArrayNode)nodes).add(other);keys.add(fallbackKey);
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
                    t.videoContext().isBlank() && old!=null?old.videoContext():t.videoContext(),t.videoAtMs().isEmpty() && old!=null?old.videoAtMs():t.videoAtMs()));
        }
        var saved=new DanmakuClassification(progress.offset()+batch.size(),List.copyOf(merged.values()),videoId);context.saveReportClassification(id,saved);
        cache.put(context.scope(),data,video,saved,context.reportCacheVariant());
        return new ToolResult("create_danmaku_report",Map.of("evidence_id",id,"classification",saved.view(data.audience())),id);
    }
    private void checkBudget(ToolContext context,int needed){
        Integer available=context.remainingModelCalls();
        if(available!=null && available<needed)throw new AgentException("REPORT_BUDGET_INSUFFICIENT","Remaining report batches need at least "+needed+" model calls; only "+available+" remain. Stop without retrying or publishing a partial report.");
    }
}
