package com.tianmu.agent.application;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.Dataset;
import java.text.Normalizer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Versioned JSON presentation contract. Evidence and classification remain separate from display examples. */
public record CreatorReportDocument(String schemaVersion,String title,String period,boolean synthetic,Metrics metrics,
        List<String> conclusions,VideoSummary video,List<TopicCard> topics,List<Viewer> activeViewers,
        List<Bar> playback,List<Bar> activity,String activitySummary,long unknownPlayback,String dataAsOf,String videoUrl) {
    public record Metrics(long messages,long viewers,int topics,long analyzedMessages) {}
    public record VideoSummary(String text,int sampledPositions) {}
    public record Link(String label,String url) {}
    public record Quote(String text,long occurrences,Link playback) {}
    public record TopicCard(String title,String observation,String recommendation,long messages,String videoContext,List<Link> videoMoments,List<Quote> quotes){
        public TopicCard{videoMoments=List.copyOf(videoMoments);quotes=List.copyOf(quotes);}
    }
    public record Viewer(String name,long messages,int activeDays) {}
    public record Bar(String label,long count,String url) {}
    public CreatorReportDocument{
        conclusions=List.copyOf(conclusions);topics=List.copyOf(topics);activeViewers=List.copyOf(activeViewers);playback=List.copyOf(playback);activity=List.copyOf(activity);
    }
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");
    private static final DateTimeFormatter SHORT=DateTimeFormatter.ofPattern("M月d日 HH:mm");
    public static CreatorReportDocument from(Dataset data,List<Topic> topics,VideoContent.Facts video){
        Overview audience=Objects.requireNonNull(data.audience());Map<String,TextGroup> groups=new HashMap<>();audience.corpus().forEach(g->groups.put(g.id(),g));
        List<Topic> sorted=topics.stream().sorted(Comparator.comparingLong(Topic::messages).reversed()).toList();
        List<TopicCard> cards=new ArrayList<>();List<String> shown=new ArrayList<>();
        for(Topic topic:sorted){
            Set<String> theme=grams(normalized(topic.title()+topic.observation()));
            List<TextGroup> candidates=topic.sampleIds().stream().map(groups::get).filter(Objects::nonNull)
                    .filter(g->!g.text().isBlank()).sorted(Comparator.<TextGroup>comparingDouble(g->relevance(g.text(),theme)).reversed()
                            .thenComparing(Comparator.comparingLong(TextGroup::count).reversed()).thenComparingInt(g->g.text().length())).toList();
            // Prefer short readable original quotes; never manufacture variants to fill three slots.
            if(candidates.stream().anyMatch(g->g.text().codePointCount(0,g.text().length())<=180))candidates=candidates.stream().filter(g->g.text().codePointCount(0,g.text().length())<=180).toList();
            if(candidates.stream().anyMatch(g->relevance(g.text(),theme)>0))candidates=candidates.stream().filter(g->relevance(g.text(),theme)>0).toList();
            List<Quote> quotes=new ArrayList<>();
            for(TextGroup group:candidates){
                String key=normalized(group.text());if(key.isEmpty() || shown.stream().anyMatch(old->similar(old,key)))continue;
                shown.add(key);quotes.add(new Quote(group.text(),group.count(),group.playbackMs()==null?null:link(data.query().videoId(),group.playbackMs(),position(group.playbackMs()))));
                if(quotes.size()==3)break;
            }
            cards.add(new TopicCard(topic.title(),topic.observation(),topic.recommendation(),topic.messages(),topic.videoContext(),
                    topic.videoAtMs().stream().map(at->link(data.query().videoId(),at,"看 "+position(at)+" 的画面")).toList(),quotes));
        }
        List<String> conclusions=new ArrayList<>();
        if(sorted.isEmpty())conclusions.add("这段时间还没有弹幕，暂时无法判断观众反馈。");
        else{
            String themes=String.join("、",sorted.stream().limit(3).map(Topic::title).toList());
            conclusions.add("讨论主要围绕“"+themes+"”展开。");
            Topic first=sorted.get(0);conclusions.add("其中，“"+first.title()+"”涉及的弹幕最多，共 "+first.messages()+" 条。具体反馈与可以采取的行动见下方话题。");
        }
        List<Viewer> viewers=new ArrayList<>();int rank=0;
        for(Interaction fan:audience.interactions().stream().limit(10).toList()){rank++;viewers.add(new Viewer(fan.nickname().isBlank()?"观众 "+rank:fan.nickname(),fan.messages(),fan.activeDays()));}
        List<Bar> playback=audience.playback().stream().map(p->new Bar(position(p.startMs())+" — "+position(p.endMs()),p.count(),"/video/"+data.query().videoId()+"?t="+p.startMs()/1000.0)).toList();
        long duration=Duration.between(data.query().start(),data.query().end()).getSeconds(),width=Math.max(1,(duration+11)/12);
        var peak=data.buckets().stream().max(Comparator.comparingLong(com.tianmu.agent.core.DanmakuTypes.Bucket::count)).orElseThrow();
        String peakText=peak.count()==0?"当前时段没有讨论记录。":"最活跃的时段是 "+SHORT.format(data.query().start().plusSeconds(peak.index()*width))+" — "+SHORT.format(data.query().start().plusSeconds(Math.min(duration,(peak.index()+1)*width)))+"，收到 "+peak.count()+" 条弹幕。";
        List<Bar> activity=data.buckets().stream().filter(b->b.index()*width<duration).map(b->new Bar(SHORT.format(data.query().start().plusSeconds(b.index()*width)),b.count(),null)).toList();
        return new CreatorReportDocument("1.0","观众都在聊什么",DATE.format(data.query().start())+" — "+DATE.format(data.query().end())+" · 北京时间",
                data.demo() || audience.interactions().stream().anyMatch(f->f.nickname().startsWith("模拟")),new Metrics(data.totalMessages(),data.uniqueSenders(),cards.size(),audience.corpusMessages()),
                conclusions,video==null?null:new VideoSummary(video.summary(),video.sampledAtMs().size()),cards,viewers,playback,activity,peakText,audience.unknownPlayback(),
                DATE.format(LocalDateTime.ofInstant(data.dataAsOf(),DanmakuTypes.ZONE)),"/video/"+data.query().videoId());
    }
    private static Link link(long video,long at,String label){return new Link(label,"/video/"+video+"?t="+at/1000.0);}
    private static String position(long ms){return String.format(Locale.ROOT,"%02d:%02d",ms/60000,ms/1000%60);}
    private static String normalized(String text){return Normalizer.normalize(text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+","");}
    private static double relevance(String text,Set<String> theme){Set<String> terms=grams(normalized(text));int size=terms.size();terms.retainAll(theme);return size==0?0:terms.size()/Math.sqrt(size);}
    private static boolean similar(String a,String b){
        if(a.equals(b))return true;if(Math.min(a.length(),b.length())<12)return false;
        if((a.contains(b)||b.contains(a)) && Math.min(a.length(),b.length())>=Math.max(a.length(),b.length())*.8)return true;
        Set<String> x=grams(a),y=grams(b),union=new HashSet<>(x);union.addAll(y);x.retainAll(y);
        return !union.isEmpty() && x.size()/(double)union.size()>=.82;
    }
    private static Set<String> grams(String text){Set<String> result=new HashSet<>();for(int i=0;i+1<text.length();i++)result.add(text.substring(i,i+2));return result;}
}
