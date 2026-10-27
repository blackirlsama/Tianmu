package com.tianmu.agent.core;

import com.tianmu.agent.core.AudienceData.*;
import java.util.*;

/** Classification state checkpointed through private evidence; the runtime cache is disposable. */
public record DanmakuClassification(int offset, List<Topic> topics, String videoEvidenceId, boolean merged) {
    private static final int MAX_GROUPS_PER_BATCH = 40;
    public DanmakuClassification(int offset,List<Topic> topics,String videoEvidenceId){this(offset,topics,videoEvidenceId,false);}
    public DanmakuClassification(int offset,List<Topic> topics){this(offset,topics,null);}
    public DanmakuClassification { if(offset<0)throw new IllegalArgumentException();topics=List.copyOf(topics); }
    public static DanmakuClassification empty(){return new DanmakuClassification(0,List.of());}
    public static List<TextGroup> batch(Overview data,int offset){
        if(offset<0 || offset>data.corpus().size())throw new AgentException("INVALID_REPORT","Invalid batch position");
        List<TextGroup> result=new ArrayList<>();int chars=0;Set<InputKey> unique=new HashSet<>();
        for(int i=offset;i<data.corpus().size();i++){
            TextGroup group=data.corpus().get(i);
            int size=group.text().length()+group.context().length();
            var key=new InputKey(group.text(),group.source(),group.context(),group.author(),group.playbackMs());
            if(!unique.contains(key)){
                if(!result.isEmpty() && (unique.size()>=MAX_GROUPS_PER_BATCH || chars+size>12000))break;
                unique.add(key);chars+=size;
            }
            result.add(group);
        }
        return List.copyOf(result);
    }
    public record InputGroup(String id,List<TextGroup> members) {}
    private record InputKey(String text,String source,String context,boolean author,Long playbackMs) {}
    /** Exact compression only, scoped to a batch. Every original remains a coverage member. */
    public static List<InputGroup> inputs(Overview data,int offset){
        Map<InputKey,List<TextGroup>> grouped=new LinkedHashMap<>();Map<InputKey,String> aliases=new HashMap<>();
        var batch=batch(data,offset);
        for(int i=0;i<batch.size();i++){
            var g=batch.get(i);var key=new InputKey(g.text(),g.source(),g.context(),g.author(),g.playbackMs());
            grouped.computeIfAbsent(key,ignored->new ArrayList<>()).add(g);aliases.putIfAbsent(key,"G"+(offset+i+1));
        }
        return grouped.entrySet().stream().map(e->new InputGroup(aliases.get(e.getKey()),List.copyOf(e.getValue()))).toList();
    }
    public Map<String,Object> view(Overview data){
        List<Map<String,Object>> rows=new ArrayList<>();
        for(InputGroup input:inputs(data,offset)){
            TextGroup g=input.members().get(0);Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",input.id());row.put("text",g.text());row.put("count",input.members().stream().mapToLong(TextGroup::count).sum());
            row.put("playbackMs",g.playbackMs());row.put("source",g.source());row.put("original_id",g.originalId());row.put("author",g.author());
            if(input.members().size()>1)row.put("original_records",input.members().size());
            if(!g.context().isEmpty())row.put("reply_context",g.context());rows.add(row);
        }
        return Map.of("offset",offset,"processed_groups",offset,"total_groups",data.corpus().size(),"processed_messages",topics.stream().mapToLong(Topic::messages).sum(),
                "total_messages",data.totalMessages(),"complete",offset==data.corpus().size(),
                "groups",rows,"remaining_batches",remainingBatches(data,offset),
                "topics",topics.stream().map(t->Map.of("topic_key",t.id(),"title",t.title(),"observation",t.observation(),"recommendation",t.recommendation(),"messages",t.messages())).toList());
    }
    public static int remainingBatches(Overview data,int offset){
        if(offset<0 || offset>data.corpus().size())throw new AgentException("INVALID_REPORT","Invalid batch position");
        int count=0;while(offset<data.corpus().size()){offset+=batch(data,offset).size();count++;}return count;
    }
}
