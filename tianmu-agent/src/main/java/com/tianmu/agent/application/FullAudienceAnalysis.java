package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.model.AudienceModelPort;
import com.tianmu.agent.runtime.control.CallControl;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

/** Every original text is read in a bounded batch. Only reviewed batch conclusions are merged. */
@Service
public final class FullAudienceAnalysis {
    private final ObjectMapper json;
    private final ObjectProvider<AudienceModelPort> models;
    public FullAudienceAnalysis(ObjectMapper json,ObjectProvider<AudienceModelPort> models){this.json=json;this.models=models;}

    public List<Topic> analyze(Dataset source,VideoContent.Facts video,CallControl control){
        Overview all=source.audience();
        if(all==null || all.corpusMessages()!=source.totalMessages() || all.corpus().stream().anyMatch(TextGroup::truncated))
            throw new AgentException("FULL_ANALYSIS_INCOMPLETE","全部弹幕尚未读取完整，请缩小时间范围后重试；不会用抽样代替全量分析。");
        if(all.corpus().isEmpty())return List.of();
        AudienceModelPort model=models.getIfAvailable();
        if(model==null)throw new AgentException("MODEL_NOT_CONFIGURED","请先配置弹幕分析模型。");
        List<List<TextGroup>> batches=new ArrayList<>();List<TextGroup> batch=new ArrayList<>();int chars=0;
        for(TextGroup group:all.corpus()){
            if(!batch.isEmpty() && (batch.size()>=40 || chars+group.text().length()>12000)){
                batches.add(List.copyOf(batch));batch.clear();chars=0;
            }
            batch.add(group);chars+=group.text().length();
        }
        if(!batch.isEmpty())batches.add(List.copyOf(batch));
        List<Topic> parts=new ArrayList<>();
        for(List<TextGroup> groups:batches){
            control.checkpoint();
            parts.addAll(classify(model,subset(source,groups),video,control));
        }
        if(batches.size()==1)return List.copyOf(parts);
        Map<String,Topic> originals=new LinkedHashMap<>();List<TextGroup> summaries=new ArrayList<>();
        Map<String,TextGroup> raw=new HashMap<>();all.corpus().forEach(g->raw.put(g.id(),g));
        for(Topic part:parts){
            String id=part.sampleIds().get(0);originals.put(id,part);
            long[] bins=new long[12];boolean hasTrend=true;
            for(String member:part.sampleIds()){
                List<Long> trend=raw.get(member).trend();
                if(trend.isEmpty()){hasTrend=false;break;}
                for(int i=0;i<12;i++)bins[i]=Math.addExact(bins[i],trend.get(i));
            }
            String text="批次结论："+part.title()+"。观众表达："+part.observation()+"。建议："+part.recommendation();
            summaries.add(new TextGroup(id,text,part.messages(),null,false,hasTrend?Arrays.stream(bins).boxed().toList():List.of()));
        }
        // The second pass merges conclusions, never selects a subset of original messages.
        List<Topic> merged=classify(model,subset(source,summaries),video,control);List<Topic> result=new ArrayList<>();
        for(Topic topic:merged){
            List<String> members=topic.sampleIds().stream().flatMap(id->originals.get(id).sampleIds().stream()).toList();
            result.add(new Topic(topic.id(),topic.title(),topic.observation(),topic.recommendation(),members,topic.messages(),topic.videoContext(),topic.videoAtMs()));
        }
        if(result.stream().mapToLong(Topic::messages).sum()!=source.totalMessages())throw incomplete();
        return List.copyOf(result);
    }
    private List<Topic> classify(AudienceModelPort model,Dataset data,VideoContent.Facts video,CallControl control){
        for(int attempt=0;attempt<2;attempt++){
            control.checkpoint();
            String output=model.summarize(data,List.of(),video,control);
            try {
                List<Topic> topics=AudienceTopics.parse(json,output,data.audience(),video);
                long groups=topics.stream().mapToLong(t->t.sampleIds().size()).sum();
                if(groups==data.audience().corpus().size() && topics.stream().mapToLong(Topic::messages).sum()==data.totalMessages())return topics;
            }catch(AgentException ignored){ /* Retry incomplete or invalid classification once. */ }
        }
        throw incomplete();
    }
    private static AgentException incomplete(){return new AgentException("FULL_ANALYSIS_INCOMPLETE","部分弹幕尚未完成归类，本次未生成完整报告，请重试。");}
    private static Dataset subset(Dataset source,List<TextGroup> groups){
        long total=groups.stream().mapToLong(TextGroup::count).sum();long[] counts=new long[12];
        for(TextGroup g:groups){if(g.trend().isEmpty())counts[0]+=g.count();else for(int i=0;i<12;i++)counts[i]+=g.trend().get(i);}
        List<Bucket> buckets=new ArrayList<>();for(int i=0;i<12;i++)buckets.add(new Bucket(i,counts[i]));
        return new Dataset(source.query(),total,0,buckets,List.of(),source.dataAsOf(),source.source(),source.demo(),new Overview(List.of(),List.of(),total,groups,total,total));
    }
}
