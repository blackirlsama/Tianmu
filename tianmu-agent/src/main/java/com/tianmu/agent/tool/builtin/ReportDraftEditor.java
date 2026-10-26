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
    ReportDraftEditor(ObjectMapper json){this.json=json.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);}
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
        List<String> keys=new ArrayList<>();JsonNode nodes;
        try{
            nodes=json.readTree(ToolArguments.text(call,"topics_json"));
            if(nodes.isObject() && nodes.size()==1 && nodes.has("topics"))nodes=nodes.get("topics");
            if(!nodes.isArray())throw new IllegalArgumentException();
            Map<String,String> aliases=new LinkedHashMap<>();Set<String> canonical=new HashSet<>(),assigned=new HashSet<>();
            for(int i=0;i<batch.size();i++){aliases.put("G"+(progress.offset()+i+1),batch.get(i).id());canonical.add(batch.get(i).id());}
            for(JsonNode node:nodes){
                String key=node.path("topic_key").asText();if(!key.matches("T[1-6]") || keys.contains(key))throw new IllegalArgumentException();keys.add(key);((ObjectNode)node).remove("topic_key");
                JsonNode refs=node.path("sample_ids");if(!refs.isArray())throw new IllegalArgumentException();
                for(int i=0;i<refs.size();i++){
                    if(!refs.get(i).isTextual())throw new IllegalArgumentException();String supplied=refs.get(i).textValue();
                    String member=aliases.get(supplied);if(member==null && canonical.contains(supplied))member=supplied;
                    if(member==null)throw new AgentException("INVALID_REPORT","Unknown batch ID: "+supplied+". Use only IDs in the current classification.groups.");
                    if(!assigned.add(member))throw new AgentException("INVALID_REPORT","Repeated batch ID: "+supplied+". Assign each ID once.");
                    ((com.fasterxml.jackson.databind.node.ArrayNode)refs).set(i,com.fasterxml.jackson.databind.node.TextNode.valueOf(member));
                }
            }
            List<String> missing=aliases.entrySet().stream().filter(e->!assigned.contains(e.getValue())).map(Map.Entry::getKey).toList();
            if(!missing.isEmpty())throw new AgentException("INVALID_REPORT","Missing batch IDs: "+String.join(",",missing)+". Resubmit this entire batch, including these IDs exactly once.");
        }catch(AgentException e){throw e;}
        catch(Exception e){throw new AgentException("INVALID_REPORT","topics_json must be an array or {\"topics\":[...]}; use topic_key T1..T6 and assign every current batch ID exactly once.");}
        long total=batch.stream().mapToLong(TextGroup::count).sum();
        Overview subset=new Overview(List.of(),List.of(),total,batch,total,total);
        List<Topic> parsed=AudienceTopics.parse(json,nodes.toString(),subset,video);
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
        return new ToolResult("create_danmaku_report",Map.of("evidence_id",id,"classification",saved.view(data.audience())),id);
    }
}
