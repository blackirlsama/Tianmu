package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.DanmakuTypes.Dataset;
import com.tianmu.agent.resource.ResourceFiles;
import com.tianmu.agent.runtime.store.AnalysisCacheStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Reuses only identical authorized corpus/video snapshots. Run-local evidence IDs never enter the cache. */
@Component
public final class ReportAnalysisCache implements com.tianmu.agent.runtime.RunCompletionListener {
    private final ObjectMapper json;
    private final String revision;
    private record Cached(DanmakuClassification progress,Instant expiresAt) {}
    private final AnalysisCacheStore store;
    private final Clock clock;
    private final Cache<String,Cached> entries;
    private record Lease(UUID runId,Instant expiresAt) {}
    private final Cache<String,Lease> leases=Caffeine.newBuilder().maximumSize(256).expireAfterWrite(Duration.ofMinutes(2)).build();
    private final Set<UUID> activeRuns=java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ReportAnalysisCache(ObjectMapper json) { this(json,"standalone"); }
    public ReportAnalysisCache(ObjectMapper json,String model){this(json,model,null,Clock.systemUTC());}
    @org.springframework.beans.factory.annotation.Autowired
    public ReportAnalysisCache(ObjectMapper json,
            @org.springframework.beans.factory.annotation.Value("${agent.models.harness-model:unconfigured}") String model,
            org.springframework.beans.factory.ObjectProvider<AnalysisCacheStore> stores) {this(json,model,stores.getIfAvailable(),Clock.systemUTC());}
    public ReportAnalysisCache(ObjectMapper json,String model,AnalysisCacheStore store,Clock clock) {
        this.json=json;this.store=store;this.clock=clock;
        entries=Caffeine.newBuilder().maximumSize(64).expireAfterWrite(store==null?Duration.ofHours(1):Duration.ofSeconds(30)).build();
        StringBuilder resources=new StringBuilder("report-analysis-cache-v1\n").append(model);
        for(String path:List.of("skills/audience-report/SKILL.md","skills/audience-report/references/topic-merging.md",
                "tools/create_danmaku_report/tool.json","prompts/harness/system.md"))
            resources.append('\n').append(ResourceFiles.text(new ClassPathResource(path),65536));
        revision=digest(resources.toString().getBytes(StandardCharsets.UTF_8));
    }
    public DanmakuClassification get(AccessScope owner,Dataset data,VideoContent.Facts video) {
        return get(owner,data,video,"");
    }
    public DanmakuClassification get(AccessScope owner,Dataset data,VideoContent.Facts video,String variant) {
        String key=key(owner,data,video,variant);if(key==null)return null;
        Cached local=entries.getIfPresent(key);
        if(local!=null && clock.instant().isBefore(local.expiresAt()))return local.progress();
        entries.invalidate(key);
        if(store==null)return null;
        AnalysisCacheStore.Entry saved=store.get(key);
        if(saved==null || !clock.instant().isBefore(saved.expiresAt()) || saved.json().length()>1048576)return null;
        try {
            var progress=json.readValue(saved.json(),DanmakuClassification.class);
            if(progress.videoEvidenceId()!=null || saved.rank()!=rank(progress) || progress.offset()>data.audience().corpus().size()
                    || progress.merged() && progress.offset()!=data.audience().corpus().size())return null;
            var prefix=data.audience().corpus().subList(0,progress.offset());
            Set<String> expected=new HashSet<>();prefix.forEach(g->expected.add(g.id()));
            List<String> members=progress.topics().stream().flatMap(t->t.sampleIds().stream()).toList();
            if(members.size()!=expected.size() || !expected.equals(new HashSet<>(members))
                    || progress.topics().stream().mapToLong(t->t.messages()).sum()!=prefix.stream().mapToLong(g->g.count()).sum())return null;
            entries.put(key,new Cached(progress,saved.expiresAt()));return progress;
        } catch(Exception e){return null;}
    }
    public void put(AccessScope owner,Dataset data,VideoContent.Facts video,DanmakuClassification progress) {
        put(owner,data,video,progress,"");
    }
    public void put(AccessScope owner,Dataset data,VideoContent.Facts video,DanmakuClassification progress,String variant) {
        String key=key(owner,data,video,variant);if(key==null)return;
        var detached=new DanmakuClassification(progress.offset(),progress.topics(),null,progress.merged());
        Instant expires=clock.instant().plus(Duration.ofHours(1));
        entries.asMap().compute(key,(ignored,old)->old==null || !clock.instant().isBefore(old.expiresAt()) || rank(detached)>rank(old.progress())?new Cached(detached,expires):old);
        if(store!=null)try{store.put(key,new AnalysisCacheStore.Entry(rank(detached),json.writeValueAsString(detached),expires));}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Cannot encode report checkpoint",e);}
    }
    private static long rank(DanmakuClassification value){return (long)value.offset()*2+(value.merged()?1:0);}
    public void claim(AccessScope owner,Dataset data,VideoContent.Facts video,String variant,UUID runId){
        String key=key(owner,data,video,variant);if(key==null || runId==null)return;
        Lease current=leases.asMap().compute(key,(ignored,old)->old==null || !clock.instant().isBefore(old.expiresAt()) || old.runId().equals(runId)
                ?new Lease(runId,clock.instant().plusSeconds(120)):old);
        if(!current.runId().equals(runId))throw busy();
        if(store!=null && !store.acquire(key,runId)){leases.asMap().remove(key,current);throw busy();}
        activeRuns.add(runId);
    }
    private AgentException busy(){return new AgentException("REPORT_IN_PROGRESS","The same authorized snapshot is already being analyzed; use the existing task instead of restarting it");}
    public void completed(com.tianmu.agent.runtime.RunState run,com.tianmu.agent.core.AgentTypes.Snapshot snapshot){release(run.id);}
    public void release(UUID runId){
        if(!activeRuns.remove(runId))return;
        leases.asMap().entrySet().removeIf(e->e.getValue().runId().equals(runId));if(store!=null)store.release(runId);
    }
    private String key(AccessScope owner,Dataset data,VideoContent.Facts video,String variant) {
        if(owner==null || data.audience()==null || !owner.videoIds().contains(data.query().videoId()))return null;
        try {
            ObjectNode input=json.createObjectNode();input.put("revision",revision);input.put("tenant",owner.tenantId());
            input.put("actor",owner.userId());input.put("videoId",data.query().videoId());
            input.put("source",data.source());input.put("demo",data.demo());
            input.put("request",Objects.toString(variant,""));
            input.set("range",json.valueToTree(data.query()));
            input.set("corpus",json.valueToTree(data.audience().corpus()));
            if(video!=null){ObjectNode facts=json.valueToTree(video);facts.remove("analyzedAt");input.set("video",facts);}
            // Time-dependent observations are reusable only within the same resolved query range.
            // Statistics still come from freshly authorized current evidence.
            return digest(json.writeValueAsBytes(input));
        } catch(Exception e) { throw new IllegalStateException("Cannot fingerprint report snapshot",e); }
    }
    private static String digest(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
