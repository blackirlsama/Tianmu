package com.tianmu.agent.core;

import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import java.time.*;
import java.util.*;

/** Source-aware report input. Compression never discards a comment's identity or reply context. */
public final class AudienceReportData {
    private AudienceReportData() {}
    public record Snapshot(Dataset danmaku, VideoComments.Dataset comments,long uniqueAudience) {}
    public record Sources(long danmaku,long danmakuAuthors,long danmakuViewers,long comments,long commentAuthors,
                          long commentViewers,long contextComments,long uniqueViewers,Instant snapshotAt) {
        public Sources {
            if(danmaku<0 || danmakuAuthors<0 || danmakuAuthors>danmaku || danmakuViewers<0 || danmakuViewers>danmaku-danmakuAuthors
                    || comments<0 || commentAuthors<0 || commentAuthors>comments || commentViewers<0 || commentViewers>comments-commentAuthors
                    || contextComments<0 || uniqueViewers<Math.max(danmakuViewers,commentViewers) || uniqueViewers>Math.addExact(danmakuViewers,commentViewers)
                    || snapshotAt==null)throw new IllegalArgumentException("Invalid source counts");
        }
    }
    public static Dataset combine(Snapshot snapshot,String owner) {
        Dataset d=Objects.requireNonNull(snapshot.danmaku());VideoComments.Dataset c=Objects.requireNonNull(snapshot.comments());
        if(!d.query().equals(c.query()) || d.audience()==null || d.audience().corpusMessages()!=d.totalMessages() || d.sources()!=null
                || d.dataAsOf().isAfter(Instant.now().plusSeconds(5)) || c.dataAsOf().isAfter(Instant.now().plusSeconds(5))
                || d.audience().corpus().stream().anyMatch(g->!g.source().equals("DANMAKU"))
                || c.comments().stream().anyMatch(row->row.author()!=owner.equals(row.userId())))
            throw new AgentException("INVALID_EVIDENCE","Feedback sources have inconsistent scopes");
        List<TextGroup> groups=new ArrayList<>();
        for(TextGroup g:d.audience().corpus())groups.add(new TextGroup(Integer.toString(groups.size()+1),g.text(),g.count(),g.playbackMs(),g.truncated(),g.trend(),"DANMAKU",g.originalId(),"",g.author()));
        Map<String,VideoComments.Comment> byId=new HashMap<>();c.comments().forEach(row->byId.put(row.id(),row));
        long seconds=Duration.between(d.query().start(),d.query().end()).getSeconds(),width=Math.max(1,(seconds+11)/12);
        long[] bins=new long[12];for(Bucket b:d.buckets())bins[b.index()]=b.count();
        for(var row:c.comments())if(row.inPeriod()) {
            int bin=Math.toIntExact(Duration.between(d.query().start(),row.createdAt().atZone(DanmakuTypes.ZONE).toLocalDateTime()).getSeconds()/width);
            if(bin<0 || bin>=12)throw new AgentException("INVALID_EVIDENCE","Comment outside report range");
            bins[bin]=Math.addExact(bins[bin],1);List<Long> trend=new ArrayList<>(Collections.nCopies(12,0L));trend.set(bin,1L);
            groups.add(new TextGroup(Integer.toString(groups.size()+1),row.text(),1,null,false,trend,"COMMENT",row.id(),context(row,byId),row.author()));
        }
        if(groups.size()>5000)throw new AgentException("FULL_ANALYSIS_TOO_LARGE","联合反馈超过当前完整分析容量；请缩小区间，不能抽样生成报告。");
        long authorDanmaku=d.audience().corpus().stream().filter(TextGroup::author).mapToLong(TextGroup::count).sum();
        long total=Math.addExact(d.totalMessages(),c.totalComments());
        Sources sources=new Sources(d.totalMessages(),authorDanmaku,d.uniqueSenders()-(authorDanmaku>0?1:0),c.totalComments(),c.totalComments()-c.audienceComments(),
                c.uniqueAudience(),c.comments().size()-c.totalComments(),snapshot.uniqueAudience(),d.dataAsOf().isBefore(c.dataAsOf())?d.dataAsOf():c.dataAsOf());
        List<Bucket> buckets=new ArrayList<>();for(int i=0;i<12;i++)buckets.add(new Bucket(i,bins[i]));
        var audience=new Overview(d.audience().interactions().stream().filter(f->!f.userId().equals(owner)).toList(),d.audience().playback(),
                Math.addExact(d.audience().unknownPlayback(),c.totalComments()),groups,total,total);
        return new Dataset(d.query(),total,sources.uniqueViewers(),buckets,List.of(),sources.snapshotAt(),"mysql:audience:retained-v1",d.demo(),audience,sources);
    }
    private static String context(VideoComments.Comment row,Map<String,VideoComments.Comment> byId) {
        List<String> ancestors=new ArrayList<>();String id=row.parentId();Set<String> seen=new HashSet<>();
        while(id!=null && seen.add(id)) {
            var parent=byId.get(id);
            if(parent==null){ancestors.add("父评论 "+id+" 已缺失，不能推断其原文。");break;}
            ancestors.add("评论 "+parent.id()+" · "+(parent.author()?"作者":"观众")+(parent.inPeriod()?"":" · 区间外，仅作上下文")+"："+parent.text());id=parent.parentId();
        }
        Collections.reverse(ancestors);String context=String.join("\n",ancestors);
        if(context.length()>32768)throw new AgentException("COMMENT_CONTEXT_TOO_LARGE","回复上下文超过完整分析容量。");
        return context;
    }
    static void validate(Sources s,Overview data,long total) {
        if(data==null || s.danmaku()+s.comments()!=total)throw new IllegalArgumentException("Source coverage mismatch");
        long d=0,c=0,da=0,ca=0;
        for(TextGroup g:data.corpus())if(g.source().equals("COMMENT")){c=Math.addExact(c,g.count());if(g.author())ca=Math.addExact(ca,g.count());}
        else{d=Math.addExact(d,g.count());if(g.author())da=Math.addExact(da,g.count());}
        if(d!=s.danmaku() || c!=s.comments() || da!=s.danmakuAuthors() || ca!=s.commentAuthors())throw new IllegalArgumentException("Source records do not match exact totals");
    }
}
