package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.tool.builtin.CreateDanmakuReportTool;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class ReportAnalysisCacheTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final AccessScope owner=new AccessScope("tenant","creator",Set.of(1001L));
    VideoContent.Facts video(String version){return new VideoContent.Facts("video-content-v1",1001,version,0,3000,
            "草地上的小鸭",List.of(new VideoContent.Moment(1000,"小鸭看向镜头")),"","SAMPLED_FRAMES",List.of(1000L),"vision",Instant.now());}
    @Test void unchangedFullHistoryReusesClassificationAcrossCutoffsButNeverAcrossNewOriginals(){
        var base=new ReportSkillFlowTest().data(1);var all=new Query(1001,DanmakuTypes.ALL_TIME_START,base.query().end());
        var data=new Dataset(all,1,1,base.buckets(),List.of(),base.dataAsOf(),base.source(),false,base.audience());
        var cache=new ReportAnalysisCache(json,"flash");var original=base.audience().corpus().get(0);
        var topic=new Topic("T1","小鸭日常","观众期待后续","",List.of(original.id()),1,"",List.of());
        cache.put(owner,data,null,new DanmakuClassification(1,List.of(topic),null,true));
        var later=new Dataset(new Query(1001,DanmakuTypes.ALL_TIME_START,all.end().plusSeconds(1)),1,1,base.buckets(),List.of(),Instant.now(),base.source(),false,base.audience());
        assertNotNull(cache.get(owner,later,null));
        var changed=new TextGroup("9001",original.text(),1,original.playbackMs(),false);
        var overview=new Overview(List.of(),base.audience().playback(),0,List.of(changed),1,1);
        var changedData=new Dataset(later.query(),1,1,base.buckets(),List.of(),Instant.now(),base.source(),false,overview);
        assertNull(cache.get(owner,changedData,null),"A new original ID invalidates reuse even with identical wording");
    }
    @Test void insufficientRemainingCallsStopBeforeClassifyingADoomedReport() throws Exception {
        var fixtures=new ReportSkillFlowTest();var data=fixtures.data(360);var state=new AtomicReference<>(DanmakuClassification.empty());
        var context=fixtures.context(data,state);when(context.remainingModelCalls()).thenReturn(2);
        var error=assertThrows(AgentException.class,()->new CreateDanmakuReportTool(json).execute(fixtures.batch(data,state.get()),context));
        assertEquals("REPORT_BUDGET_INSUFFICIENT",error.code());assertEquals(0,state.get().offset());
    }

    @Test void snapshotReuseIsScopedToActorCorpusVideoAndModelAndDropsRunEvidenceIds(){
        var data=new ReportSkillFlowTest().data(81);var facts=video("v1");var cache=new ReportAnalysisCache(json,"model-a");
        var partial=new DanmakuClassification(40,List.of(),"ev_old_run");cache.put(owner,data,facts,partial);
        var hit=cache.get(owner,data,video("v1"));assertNotNull(hit);assertEquals(40,hit.offset());assertNull(hit.videoEvidenceId());
        assertNull(cache.get(new AccessScope("other","creator",Set.of(1001L)),data,facts));
        assertNull(cache.get(new AccessScope("tenant","other",Set.of(1001L)),data,facts));
        assertNull(cache.get(new AccessScope("tenant","creator",Set.of()),data,facts));
        assertNull(cache.get(owner,data,video("v2")));assertNull(cache.get(owner,data,null));
        assertNull(cache.get(owner,data,facts,"只分析拍摄设备"));
        var shifted=new Dataset(new Query(1001,data.query().start().minusSeconds(1),data.query().end().minusSeconds(1)),
                data.totalMessages(),data.uniqueSenders(),data.buckets(),data.samples(),data.dataAsOf(),data.source(),data.demo(),data.audience());
        assertNull(cache.get(owner,shifted,facts),"Changed time ranges must not reuse time-dependent report prose");
        var original=data.audience().corpus().get(0);List<TextGroup> groups=new ArrayList<>(data.audience().corpus());
        groups.set(0,new TextGroup(original.id(),"新评论内容",original.count(),original.playbackMs(),false));
        var changed=new Overview(data.audience().interactions(),data.audience().playback(),0,groups,81,81);
        var changedData=new Dataset(data.query(),81,data.uniqueSenders(),data.buckets(),data.samples(),data.dataAsOf(),data.source(),data.demo(),changed);
        assertNull(cache.get(owner,changedData,facts));
        cache.put(owner,data,facts,new DanmakuClassification(10,List.of(),"ev_smaller"));
        assertEquals(40,cache.get(owner,data,facts).offset(),"A slower request must not overwrite later progress");
    }

    @Test void retryResumesAcceptedBatchesAndUnchangedCompletedReportNeedsNoReclassification() throws Exception {
        var fixtures=new ReportSkillFlowTest();var data=fixtures.data(81);var cache=new ReportAnalysisCache(json,"test-model");
        var tool=new CreateDanmakuReportTool(json,cache);var firstState=new AtomicReference<>(DanmakuClassification.empty());
        var first=fixtures.context(data,firstState);when(first.scope()).thenReturn(owner);
        tool.execute(fixtures.batch(data,firstState.get()),first);assertEquals(40,firstState.get().offset());
        var resumedState=new AtomicReference<>(DanmakuClassification.empty());var resumed=fixtures.context(data,resumedState);when(resumed.scope()).thenReturn(owner);
        var reused=tool.execute(fixtures.batch(data,resumedState.get()),resumed);
        assertEquals(40,resumedState.get().offset());assertEquals(40,reused.data().get("reused_groups"));
        while(resumedState.get().offset()<81)tool.execute(fixtures.batch(data,resumedState.get()),resumed);
        var thirdState=new AtomicReference<>(DanmakuClassification.empty());var third=fixtures.context(data,thirdState);when(third.scope()).thenReturn(owner);
        var finalReuse=tool.execute(fixtures.batch(data,thirdState.get()),third);
        assertEquals(81,thirdState.get().offset());assertNotNull(finalReuse.terminalAnswer());
    }
    @Test void aFreshCacheInstanceLoadsValidatedDurableProgressAndRejectsExpiredOrCorruptRows() throws Exception {
        var rows=new java.util.concurrent.ConcurrentHashMap<String,com.tianmu.agent.runtime.store.AnalysisCacheStore.Entry>();
        var backend=new com.tianmu.agent.runtime.store.AnalysisCacheStore(){
            public Entry get(String key){return rows.get(key);}
            public void put(String key,Entry next){rows.compute(key,(ignored,old)->old==null || next.rank()>=old.rank()?next:old);}
        };
        Instant now=Instant.parse("2026-10-27T12:00:00Z");Clock clock=Clock.fixed(now,ZoneOffset.UTC);
        var first=new ReportAnalysisCache(json,"test",backend,clock);var fixtures=new ReportSkillFlowTest();var data=fixtures.data(81);
        var progress=new AtomicReference<>(DanmakuClassification.empty());var context=fixtures.context(data,progress);when(context.scope()).thenReturn(owner);
        new CreateDanmakuReportTool(json,first).execute(fixtures.batch(data,progress.get()),context);
        assertEquals(1,rows.size());assertFalse(rows.values().iterator().next().json().contains("ev_full"));
        var restarted=new ReportAnalysisCache(json,"test",backend,clock);
        assertEquals(40,restarted.get(owner,data,null).offset());
        assertNull(new ReportAnalysisCache(json,"test",backend,Clock.fixed(now.plusSeconds(3601),ZoneOffset.UTC)).get(owner,data,null));
        String key=rows.keySet().iterator().next();rows.put(key,new com.tianmu.agent.runtime.store.AnalysisCacheStore.Entry(80,"not-json",now.plusSeconds(3600)));
        assertNull(new ReportAnalysisCache(json,"test",backend,clock).get(owner,data,null));
        rows.put(key,new com.tianmu.agent.runtime.store.AnalysisCacheStore.Entry(80,json.writeValueAsString(new DanmakuClassification(40,List.of())),now.plusSeconds(3600)));
        assertNull(new ReportAnalysisCache(json,"test",backend,clock).get(owner,data,null),"Incomplete persisted membership must never be admitted");
    }
    @Test void concurrentDuplicateReportIsRejectedUntilItsOwnerReleasesTheLease(){
        var cache=new ReportAnalysisCache(json,"model");var data=new ReportSkillFlowTest().data(81);UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        cache.claim(owner,data,null,"same-request",first);cache.claim(owner,data,null,"same-request",first);
        assertEquals("REPORT_IN_PROGRESS",assertThrows(AgentException.class,()->cache.claim(owner,data,null,"same-request",second)).code());
        assertDoesNotThrow(()->cache.claim(new AccessScope("tenant","bob",Set.of(1001L)),data,null,"same-request",second));
        cache.release(first);assertDoesNotThrow(()->cache.claim(owner,data,null,"same-request",second));cache.release(second);
    }
}
