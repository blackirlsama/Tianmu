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
        List<TextGroup> result=new ArrayList<>();int chars=0;
        for(int i=offset;i<data.corpus().size();i++){
            TextGroup group=data.corpus().get(i);
            int size=group.text().length()+group.context().length();
            if(!result.isEmpty() && (result.size()>=MAX_GROUPS_PER_BATCH || chars+size>12000))break;
            result.add(group);chars+=size;
        }
        return List.copyOf(result);
    }
    public Map<String,Object> view(Overview data){
        List<TextGroup> batch=batch(data,offset);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<batch.size();i++){
            TextGroup g=batch.get(i);Map<String,Object> row=new LinkedHashMap<>();row.put("id","G"+(offset+i+1));row.put("text",g.text());row.put("count",g.count());row.put("playbackMs",g.playbackMs());row.put("source",g.source());row.put("original_id",g.originalId());row.put("author",g.author());if(!g.context().isEmpty())row.put("reply_context",g.context());rows.add(row);
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
