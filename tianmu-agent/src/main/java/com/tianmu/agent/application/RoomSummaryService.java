package com.tianmu.agent.application;

import com.github.benmanes.caffeine.cache.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.model.AudienceModelPort;
import com.tianmu.agent.query.DanmakuQueryPort;
import com.tianmu.agent.runtime.control.*;
import com.tianmu.agent.video.VideoAnalysisService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A video is a room. Poll persisted data, coalesce readers, and refresh the shared topic snapshot in bounded batches. */
@Service
public final class RoomSummaryService {
    public record View(long videoId,long version,String state,LocalDateTime start,LocalDateTime end,Instant updatedAt,
                       Dataset data,List<Topic> topics,boolean videoAnalyzed,String errorCode,boolean demo) {}
    private record Key(String tenant,String user,long video,LocalDateTime start,Set<Long> scope) {}
    private static final class Slot { volatile View view; volatile Instant checked=Instant.MIN; String fingerprint=""; VideoContent.Facts video; }
    private final Cache<Key,Slot> cache=Caffeine.newBuilder().maximumSize(100).expireAfterAccess(Duration.ofMinutes(15)).build();
    private final Map<Key,Slot> active=new HashMap<>();
    private final Clock clock;
    private final io.opentelemetry.api.trace.Tracer tracer;
    private final DanmakuQueryPort query; private final ObjectProvider<AudienceModelPort> models;
    private final VideoAnalysisService videos; private final ObjectMapper json; private final ThreadPoolTaskExecutor executor;
    @org.springframework.beans.factory.annotation.Autowired
    public RoomSummaryService(DanmakuQueryPort query,ObjectProvider<AudienceModelPort> models,VideoAnalysisService videos,
                              ObjectMapper json,@Qualifier("toolExecutor") ThreadPoolTaskExecutor executor,io.opentelemetry.api.trace.Tracer tracer){
        this(query,models,videos,json,executor,Clock.systemUTC(),tracer);
    }
    RoomSummaryService(DanmakuQueryPort query,ObjectProvider<AudienceModelPort> models,VideoAnalysisService videos,
                       ObjectMapper json,ThreadPoolTaskExecutor executor,Clock clock){
        this(query,models,videos,json,executor,clock,io.opentelemetry.api.OpenTelemetry.noop().getTracer("test"));
    }
    private RoomSummaryService(DanmakuQueryPort query,ObjectProvider<AudienceModelPort> models,VideoAnalysisService videos,
                               ObjectMapper json,ThreadPoolTaskExecutor executor,Clock clock,io.opentelemetry.api.trace.Tracer tracer){
        this.tracer=tracer;this.query=query;this.models=models;this.videos=videos;this.json=json;this.executor=executor;this.clock=clock;
    }
    public View get(long videoId,LocalDateTime start,AccessScope owner){
        if(!owner.videoIds().contains(videoId))throw new AgentException("FORBIDDEN","Video outside scope");
        LocalDateTime end=LocalDateTime.now(clock.withZone(DanmakuTypes.ZONE)).withNano(0);
        boolean rolling=start==null;
        if(start==null) start=end.minusDays(7).withSecond(0).withNano(0);
        Query bounds;
        try{bounds=new Query(videoId,start,end);bounds.checkTime(clock.instant());}catch(RuntimeException e){throw new AgentException("INVALID_ARGUMENTS","Use a past start within 30 days");}
        Key key=new Key(owner.tenantId(),owner.userId(),videoId,rolling?null:start,Set.copyOf(owner.videoIds()));
        Slot slot;
        synchronized(active){
            slot=active.containsKey(key)?active.get(key):cache.getIfPresent(key);
            if(slot==null){slot=new Slot();slot.view=new View(videoId,0,"UPDATING",start,end,null,null,List.of(),false,null,false);cache.put(key,slot);}
            if(Duration.between(slot.checked,clock.instant()).compareTo(Duration.ofSeconds(30))>=0 && !active.containsKey(key)){
                if(active.size()>=4) return busy(slot.view,"CAPACITY_EXCEEDED");
                active.put(key,slot);slot.checked=clock.instant();Slot selected=slot;
                try{executor.execute(()->refresh(key,selected,bounds,owner));}
                catch(RejectedExecutionException e){active.remove(key);slot.view=busy(slot.view,"CAPACITY_EXCEEDED");}
            }
            return active.containsKey(key)?busy(slot.view,null):slot.view;
        }
    }
    private View busy(View v,String error){return new View(v.videoId(),v.version(),"UPDATING",v.start(),v.end(),v.updatedAt(),v.data(),v.topics(),v.videoAnalyzed(),error,v.demo());}
    private void refresh(Key key,Slot slot,Query bounds,AccessScope owner){
        AtomicInteger calls=new AtomicInteger();
        CallControl control=new CallControl(clock.instant().plusSeconds(25),new CancellationToken(),new ModelBudget(32768,8192),()->{
            calls.updateAndGet(n->{if(n>=3)throw new AgentException("MODEL_CALL_BUDGET_EXCEEDED","Room refresh call limit");return n+1;});
        });
        var usage=new com.tianmu.agent.harness.trace.TraceUsage();
        var span=tracer.spanBuilder("agent.room.summary").startSpan();
        span.setAttribute("video.id",key.video());span.setAttribute("tenant.id",key.tenant());span.setAttribute("actor.id",key.user());
        try(var observed=io.opentelemetry.context.Context.current().with(span).with(com.tianmu.agent.harness.trace.TraceUsage.KEY,usage).makeCurrent()){
            Dataset data=query.query(bounds,owner,control);
            if(data==null || !data.query().equals(bounds))throw new AgentException("INVALID_EVIDENCE","Room response differs from requested range");
            if(data.dataAsOf().isAfter(clock.instant().plusSeconds(5)))throw new AgentException("INVALID_EVIDENCE","Room snapshot is from the future");
            List<Topic> topics=slot.view.topics();
            View previous=slot.view;
            // Fresh exact counts remain available even if the optional semantic refresh fails.
            slot.view=new View(key.video(),previous.version(),"UPDATING",bounds.start(),bounds.end(),previous.updatedAt(),data,topics,previous.videoAnalyzed(),null,data.demo());
            if(data.audience()==null){
                slot.view=new View(key.video(),slot.view.version()+1,"READY",bounds.start(),bounds.end(),clock.instant(),data,List.of(),false,"AUDIENCE_DATA_UNAVAILABLE",data.demo());return;
            }
            VideoContent.Facts video=null;
            if(!data.audience().corpus().isEmpty() && models.getIfAvailable()!=null) {
                try { video=videos.analyze(key.video(),null,null,"仅提供单帧可见内容供理解弹幕，不推断连续动作",owner,control); }
                catch(AgentException e) {
                    if(Set.of("CANCELLED","MODEL_CALL_BUDGET_EXCEEDED","MODEL_TOKEN_BUDGET_EXCEEDED","FORBIDDEN").contains(e.code()))throw e;
                }
            }
            String fingerprint=json.writeValueAsString(data.audience().corpus().stream().map(g->new TextGroup(g.id(),g.text(),g.count(),g.playbackMs(),g.truncated())).toList())
                    +"|"+(video==null?"NO_VIDEO":video.sourceVersion());
            if(!fingerprint.equals(slot.fingerprint)) {
                if(data.audience().corpus().isEmpty())topics=List.of();
                else if(models.getIfAvailable()!=null) {
                    topics=AudienceTopics.parse(json,models.getObject().summarize(data,topics,video,control),data.audience(),video);
                    topics=stableIds(slot.view.topics(),topics);
                } else topics=List.of();
                slot.fingerprint=fingerprint;
            }
            slot.video=video;
            slot.view=new View(key.video(),slot.view.version()+1,"READY",bounds.start(),bounds.end(),clock.instant(),data,topics,slot.video!=null,
                    !data.audience().corpus().isEmpty() && models.getIfAvailable()==null?"MODEL_NOT_CONFIGURED":null,data.demo());
        }catch(Exception e){
            View v=slot.view;
            slot.view=new View(v.videoId(),v.version(),"ERROR",v.start(),v.end(),v.updatedAt(),v.data(),v.topics(),v.videoAnalyzed(),
                    e instanceof AgentException a?a.code():"SUMMARY_UNAVAILABLE",v.demo());
        }finally{
            com.tianmu.agent.harness.trace.TraceAttributes.apply(span,usage.totals());
            span.setAttribute("room.model_calls",calls.get());span.setAttribute("room.status",slot.view.state());span.end();
            synchronized(active){cache.put(key,slot);active.remove(key);}
        }
    }
    static List<Topic> stableIds(List<Topic> previous,List<Topic> next){
        Set<String> used=new HashSet<>();List<Topic> result=new ArrayList<>();
        for(Topic t:next){
            Topic old=previous.stream().filter(p->!used.contains(p.id())).max(Comparator.comparingLong(p->p.sampleIds().stream().filter(t.sampleIds()::contains).count())).orElse(null);
            String id=old!=null && old.sampleIds().stream().anyMatch(t.sampleIds()::contains)?old.id():t.id();used.add(id);
            result.add(new Topic(id,t.title(),t.observation(),t.recommendation(),t.sampleIds(),t.messages(),t.videoContext(),t.videoAtMs()));
        }return List.copyOf(result);
    }
}
