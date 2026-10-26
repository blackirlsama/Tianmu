package com.tianmu.agent.core;

import com.tianmu.agent.core.AudienceData.*;
import java.util.*;

/** Run-local classification progress. Restarted executions fail rather than publish a partial report. */
public record DanmakuClassification(int offset, List<Topic> topics, String videoEvidenceId) {
    public DanmakuClassification(int offset,List<Topic> topics){this(offset,topics,null);}
    public DanmakuClassification { if(offset<0)throw new IllegalArgumentException();topics=List.copyOf(topics); }
    public static DanmakuClassification empty(){return new DanmakuClassification(0,List.of());}
    public static List<TextGroup> batch(Overview data,int offset){
        if(offset<0 || offset>data.corpus().size())throw new AgentException("INVALID_REPORT","Invalid batch position");
        List<TextGroup> result=new ArrayList<>();int chars=0;
        for(int i=offset;i<data.corpus().size();i++){
            TextGroup group=data.corpus().get(i);
            if(!result.isEmpty() && (result.size()>=80 || chars+group.text().length()>12000))break;
            result.add(group);chars+=group.text().length();
        }
        return List.copyOf(result);
    }
    public Map<String,Object> view(Overview data){
        List<TextGroup> batch=batch(data,offset);
        return Map.of("offset",offset,"processed_groups",offset,"total_groups",data.corpus().size(),"processed_messages",topics.stream().mapToLong(Topic::messages).sum(),
                "total_messages",data.totalMessages(),"complete",offset==data.corpus().size(),
                "groups",batch.stream().map(g->{Map<String,Object> row=new LinkedHashMap<>();row.put("id",g.id());row.put("text",g.text());row.put("count",g.count());row.put("playbackMs",g.playbackMs());return row;}).toList(),
                "topics",topics.stream().map(t->Map.of("topic_key",t.id(),"title",t.title(),"observation",t.observation(),"recommendation",t.recommendation(),"messages",t.messages())).toList());
    }
}
