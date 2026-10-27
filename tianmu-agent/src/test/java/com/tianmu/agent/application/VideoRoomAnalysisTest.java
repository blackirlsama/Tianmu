package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.config.ExecutionConfiguration;
import com.tianmu.agent.model.AudienceModelPort;
import com.tianmu.agent.video.VideoAnalysisService;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VideoRoomAnalysisTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final AccessScope owner=new AccessScope("t","alice",Set.of(1001L));
    Overview audience(){return new Overview(List.of(new Interaction("9",3,2,2,Instant.now())),List.of(new Position(0,30000,3)),1,
            List.of(new TextGroup("1","尺寸？",3,2500L,false),new TextGroup("2","颜色？",1,null,false)),4,4);}
    Dataset data(Query q){return new Dataset(q,4,2,java.util.stream.IntStream.range(0,12).mapToObj(i->new Bucket(i,i==0?4:0)).toList(),List.of(),Instant.now(),"fixture",false,audience());}
    @Test void defaultsUseFrozenSubmissionInstantAndExplicitRangesRemainUnchanged(){
        Instant at=Instant.parse("2026-10-26T13:20:33.999Z");LocalDate date=LocalDate.of(2026,10,26);
        var spec=TaskMeaning.defaults().resolve("分析视频1001弹幕并生成报告",date,at);
        assertEquals("2026-10-19T21:20:33",spec.conditions().get("start"));
        assertEquals("2026-10-26T21:20:33",spec.conditions().get("end"));
        assertEquals("danmaku.overview",TaskMeaning.defaults().resolve("视频1001有多少弹幕",date,at).type());
        var explicit=DanmakuReportTask.parse("分析视频1001从2026-10-20 10:00到2026-10-26 10:00弹幕报告",date);
        assertEquals(LocalDateTime.of(2026,10,20,10,0),explicit.start());
        assertThrows(IllegalArgumentException.class,()->DanmakuReportTask.parse("分析视频1001从2026-10-20 10:00弹幕报告",date));
    }
    @Test void topicCountsComeFromDisjointRealGroupsAndHtmlEscapesUntrustedText(){
        String source="[{\"title\":\"<script>bad</script>\",\"observation\":\"询问尺寸\",\"recommendation\":\"补充说明\",\"sample_ids\":[\"1\"]}]";
        var topics=AudienceTopics.parse(json,source,audience(),null);
        assertEquals(3,topics.get(0).messages());
        LocalDateTime end=LocalDateTime.now(DanmakuTypes.ZONE).minusSeconds(1).withNano(0);
        var dataset=data(new Query(1001,end.minusDays(7),end));
        assertThrows(AgentException.class,()->CreatorAudienceReport.render(dataset,topics,null));
        var full=new ArrayList<>(topics);full.add(new Topic("tail","颜色问题","询问颜色","补充说明",List.of("2"),1,"",List.of()));
        String html=CreatorAudienceReport.render(dataset,full,null);
        assertTrue(html.contains("&lt;script&gt;"));assertFalse(html.contains("<script>bad"));assertTrue(html.contains("尺寸？"));
        assertTrue(html.contains("3 条相关弹幕"));assertTrue(html.contains("另有 1 条缺少有效播放位置"));
        assertThrows(AgentException.class,()->AudienceTopics.parse(json,source.replace("\"1\"","\"999\""),audience(),null));
        assertThrows(AgentException.class,()->AudienceTopics.parse(json,"["+source.substring(1,source.length()-1)+","+source.substring(1,source.length()-1)+"]",audience(),null));
        assertThrows(AgentException.class,()->AudienceTopics.parse(json,source.replace("\"sample_ids\"","\"messages\":99,\"sample_ids\""),audience(),null));
    }
    @Test void videoContextRequiresActualSampledTimestamp(){
        var video=new VideoContent.Facts("video-content-v1",1001,"v",2000,6000,"静态山水",List.of(new VideoContent.Moment(2500,"山水")),"","SAMPLED_FRAMES",List.of(2500L),"vision",Instant.now());
        String source="[{\"title\":\"尺寸\",\"observation\":\"提问\",\"recommendation\":\"\",\"sample_ids\":[\"1\"],\"video_context\":\"采样画面有山水\",\"video_at_ms\":[2500]}]";
        assertEquals(1,AudienceTopics.parse(json,source,audience(),video).size());
        assertThrows(AgentException.class,()->AudienceTopics.parse(json,source,audience(),null));
        assertThrows(AgentException.class,()->AudienceTopics.parse(json,source.replace("2500","2501"),audience(),video));
    }
    @Test void refreshIsCoalescedScopedAndDoesNotRepeatModelForUnchangedCorpus() throws Exception {
        var executor=ExecutionConfiguration.standalone("room-test",1,1,4);
        AtomicReference<Instant> time=new AtomicReference<>(Instant.now());
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return Clock.fixed(time.get(),z);}public Instant instant(){return time.get();}};
        var model=mock(AudienceModelPort.class);var vision=mock(VideoAnalysisService.class);
        when(vision.analyze(anyLong(),any(),any(),anyString(),any(),any())).thenThrow(new AgentException("VIDEO_CONTENT_UNAVAILABLE","disabled"));
        when(model.summarize(any(),any(),any(),any())).thenReturn("[{\"title\":\"尺寸问题\",\"observation\":\"观众询问尺寸\",\"recommendation\":\"补充尺寸信息\",\"sample_ids\":[\"1\",\"2\"]}]");
        var beans=new StaticListableBeanFactory();beans.addBean("model",model);
        AtomicInteger reads=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var service=new RoomSummaryService((q,s,c)->{reads.incrementAndGet();entered.countDown();try{release.await(1,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return data(q);},beans.getBeanProvider(AudienceModelPort.class),vision,json,executor,clock);
        LocalDateTime start=LocalDateTime.ofInstant(time.get(),DanmakuTypes.ZONE).minusMinutes(5).withNano(0);
        try{
            assertEquals("UPDATING",service.get(1001,start,owner).state());assertTrue(entered.await(1,TimeUnit.SECONDS));
            service.get(1001,start,owner);assertEquals(1,reads.get());release.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);RoomSummaryService.View v;
            do{v=service.get(1001,start,owner);if(v.state().equals("READY"))break;Thread.sleep(10);}while(System.nanoTime()<until);
            assertEquals("READY",v.state());assertEquals(4,v.topics().get(0).messages());
            assertThrows(AgentException.class,()->service.get(1002,start,owner));
            time.set(time.get().plusSeconds(31));service.get(1001,start,owner);
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            do{v=service.get(1001,start,owner);if(v.version()==2 && v.state().equals("READY"))break;Thread.sleep(10);}while(System.nanoTime()<until);
            assertEquals(2,reads.get());verify(model,times(1)).summarize(any(),any(),any(),any());
            assertEquals(2,v.version());
        }finally{release.countDown();executor.shutdown();}
    }
    @Test void stableTopicsSurviveTitleChangesWithoutReusingAnIdTwice(){
        Topic old=new Topic("old","尺寸","","",List.of("1"),3,"",List.of());
        var next=new Topic("new","规格提问","","",List.of("1","2"),4,"",List.of());
        assertEquals("old",RoomSummaryService.stableIds(List.of(old),List.of(next)).get(0).id());
    }
    @Test void reportUsesPrivateFullEvidenceWhileToolModelViewOmitsInteractionIdentities(){
        Query q=new Query(1001,LocalDateTime.now(DanmakuTypes.ZONE).minusDays(7).withNano(0),LocalDateTime.now(DanmakuTypes.ZONE).minusSeconds(1).withNano(0));
        var dataset=data(q);var context=mock(com.tianmu.agent.tool.ToolContext.class);
        java.util.UUID id=java.util.UUID.randomUUID();
        when(context.evidence("ev_room")).thenReturn(new com.tianmu.agent.core.AgentTypes.Evidence("ev_room",id,owner,null,dataset));
        when(context.saveReport(eq("ev_room"),any())).thenReturn(new ReportRef("report","/report"));
        when(context.reportClassification("ev_room")).thenReturn(new DanmakuClassification(2,List.of(new Topic("T1","尺寸问题","观众咨询尺寸和颜色","补充信息",List.of("1","2"),4,"",List.of()))));
        var tool=new com.tianmu.agent.tool.builtin.CreateDanmakuReportTool(json);
        tool.execute(new com.tianmu.agent.core.AgentTypes.ToolCall("create_danmaku_report",Map.of("evidence_id","ev_room","sections_json","[{\"title\":\"尺寸问题\",\"observation\":\"尺寸询问\",\"recommendation\":\"补充信息\",\"sample_ids\":[\"1\"]}]")),context);
        var html=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(context).saveReport(eq("ev_room"),html.capture());
        String page=new String(html.getValue(),java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(page.contains("尺寸问题"));assertTrue(page.contains("4 条相关弹幕"));assertTrue(page.contains("尺寸？"));assertTrue(page.contains("另有 1 条缺少有效播放位置"));
        when(context.scope()).thenReturn(owner);
        when(context.query(any())).thenAnswer(i->((java.util.function.Function<com.tianmu.agent.runtime.control.CallControl,Object>)i.getArgument(0)).apply(new com.tianmu.agent.runtime.control.CallControl(Instant.now().plusSeconds(10),new com.tianmu.agent.runtime.control.CancellationToken())));
        when(context.registerDanmakuEvidence(any())).thenAnswer(i->new com.tianmu.agent.core.AgentTypes.Evidence("ev_room",id,owner,null,i.getArgument(0)));
        var queryTool=new com.tianmu.agent.tool.builtin.QueryDanmakuTool((query,scope,control)->dataset);
        var result=queryTool.execute(new com.tianmu.agent.core.AgentTypes.ToolCall("query_danmaku",Map.of("video_id",1001L,"start",q.start().toString(),"end",q.end().toString())),context);
        assertTrue(((Dataset)result.data().get("analysis")).audience().interactions().isEmpty());
        var saved=org.mockito.ArgumentCaptor.forClass(Dataset.class);verify(context).registerDanmakuEvidence(saved.capture());assertEquals(1,saved.getValue().audience().interactions().size());
    }
}
