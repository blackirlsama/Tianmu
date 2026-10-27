package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.tool.builtin.CreateDanmakuReportTool;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JointAudienceReportTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    Dataset data(){
        var end=LocalDateTime.now(DanmakuTypes.ZONE).minusMinutes(1).withNano(0);var q=new Query(1001,end.minusDays(7),end);
        var groups=List.of(new TextGroup("10","作者：拍摄设备在简介里",1,1000L,false,List.of(),"DANMAKU","10","",true),
                new TextGroup("20","镜头里的绒毛很清晰",2,2000L,false));
        var d=new Dataset(q,3,2,java.util.stream.IntStream.range(0,12).mapToObj(i->new Bucket(i,i==0?3:0)).toList(),List.of(),Instant.now(),"fixture",false,
                new Overview(List.of(),List.of(new Position(0,3000,3)),0,groups,3,3));
        Instant old=q.start().minusDays(1).atZone(DanmakuTypes.ZONE).toInstant(),at=q.start().plusHours(1).atZone(DanmakuTypes.ZONE).toInstant();
        var comments=List.of(new VideoComments.Comment("11",null,"881","作者","设备选择 <script>bad</script>",old,false,true,false),
                new VideoComments.Comment("15",null,"881","作者","照料方法",old,false,true,false),
                new VideoComments.Comment("10","11","882","甲","想知道这个",at,true,false,false),
                new VideoComments.Comment("12",null,"881","作者","作者引导问题",at,true,true,false),
                new VideoComments.Comment("13","15","883","乙","想知道这个",at,true,false,false));
        var c=new VideoComments.Dataset("video-comments-v1",q,3,2,2,comments,Instant.now(),"fixture");
        return AudienceReportData.combine(new AudienceReportData.Snapshot(d,c,2),"881");
    }
    @Test void combinedInputPreservesCountsOriginalIdsReplyContextAndAuthorRoles() throws Exception {
        var d=data();assertEquals(6,d.totalMessages());assertEquals(5,d.audience().corpus().size());
        assertEquals(3,d.sources().danmaku());assertEquals(3,d.sources().comments());assertEquals(2,d.sources().contextComments());
        assertEquals(1,d.sources().danmakuAuthors());assertEquals(1,d.sources().commentAuthors());
        var comments=d.audience().corpus().stream().filter(g->g.source().equals("COMMENT") && !g.author()).toList();
        assertEquals(2,comments.size());assertEquals(comments.get(0).text(),comments.get(1).text());assertNotEquals(comments.get(0).context(),comments.get(1).context());
        assertTrue(comments.stream().allMatch(g->g.playbackMs()==null));assertEquals(5,d.audience().corpus().stream().map(TextGroup::id).distinct().count());
        assertEquals(3,d.audience().playback().stream().mapToLong(Position::count).sum());
        assertEquals(d,json.readValue(json.writeValueAsBytes(d),Dataset.class));
    }
    @Test void globalMergeMustCoverEveryTopicBeforeOneReadableReportIsPublished() throws Exception {
        var data=data();var state=new AtomicReference<>(DanmakuClassification.empty());var context=new ReportSkillFlowTest().context(data,state);
        var tool=new CreateDanmakuReportTool(json);List<Map<String,Object>> topics=new ArrayList<>();
        topics.add(topic("T1","清晰近景",List.of("G2")));topics.add(topic("T7","作者补充",List.of("G1","G4")));
        topics.add(topic("T8","设备问题",List.of("G3")));topics.add(topic("T9","照料问题",List.of("G5")));
        var progress=tool.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","offset",0,"topics_json",json.writeValueAsString(topics))),context);
        assertNull(progress.terminalAnswer());assertEquals("MERGE_TOPICS",progress.data().get("stage"));verify(context,never()).saveReport(anyString(),any());
        var bad=List.of(merge("设备问题",List.of("T8")));
        assertThrows(AgentException.class,()->tool.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","merge_json",json.writeValueAsString(bad))),context));
        assertFalse(state.get().merged());verify(context,never()).saveReport(anyString(),any());
        var merged=List.of(merge("清晰近景",List.of("T1")),merge("作者补充",List.of("T7")),merge("设备问题",List.of("T8")),merge("照料问题",List.of("T9")));
        var result=tool.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","merge_json",json.writeValueAsString(Map.of("topics",merged)))),context);
        assertNotNull(result.terminalAnswer());assertTrue(state.get().merged());
        var document=json.convertValue(result.data().get("report_document"),CreatorReportDocument.class);
        assertTrue(document.coverage().complete());assertEquals(6,document.coverage().analyzedMessages());assertEquals(3,document.topics().size());
        assertEquals(4,document.topics().stream().mapToLong(CreatorReportDocument.TopicCard::messages).sum());
        assertFalse(document.topics().stream().flatMap(t->t.quotes().stream()).anyMatch(q->q.text().contains("作者引导")));
        assertEquals(0,document.unknownPlayback());
        String html=CreatorAudienceReport.render(document);assertTrue(html.contains("条评论及回复"));assertTrue(html.contains("两个来源可能有同一人，不相加"));
        assertTrue(html.contains("查看这条回复的上下文"));assertFalse(html.contains("<script>"));assertTrue(html.contains("&lt;script&gt;"));
        var out=Path.of("target/report-preview");Files.createDirectories(out);Files.writeString(out.resolve("joint-audience-report.html"),html);
        Files.writeString(out.resolve("joint-audience-report.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(document));
    }
    @Test void topicDisplayKeepsLongTailInsteadOfForcingSixCategories() throws Exception {
        var base=new ReportSkillFlowTest().data(8);List<TextGroup> groups=new ArrayList<>();
        for(int i=0;i<8;i++)groups.add(new TextGroup(Integer.toString(i+1),("主题"+i+"的原文").repeat(400),1,1000L,false));
        var overview=new Overview(List.of(),base.audience().playback(),0,groups,8,8);
        var d=new Dataset(base.query(),8,1,base.buckets(),List.of(),base.dataAsOf(),"fixture",false,overview,new AudienceReportData.Sources(8,0,1,0,0,0,0,1,Instant.now()));
        var state=new AtomicReference<>(DanmakuClassification.empty());var context=new ReportSkillFlowTest().context(d,state);var tool=new CreateDanmakuReportTool(json);
        while(state.get().offset()<8){
            int offset=state.get().offset();var batch=DanmakuClassification.batch(overview,offset);List<Map<String,Object>> topics=new ArrayList<>();
            for(int i=0;i<batch.size();i++)topics.add(topic("T"+(offset+i+1),"讨论主题"+(offset+i+1),List.of("G"+(offset+i+1))));
            tool.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","offset",offset,"topics_json",json.writeValueAsString(topics))),context);
        }
        List<Map<String,Object>> merge=new ArrayList<>();for(int i=1;i<=8;i++)merge.add(merge("讨论主题"+i,List.of("T"+i)));
        var result=tool.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","merge_json",json.writeValueAsString(merge))),context);
        var doc=json.convertValue(result.data().get("report_document"),CreatorReportDocument.class);assertEquals(8,doc.topics().size());
        assertTrue(CreatorAudienceReport.render(doc).contains("查看其他讨论（2 个话题）"));
    }
    @Test void pageContextIsNotASeparateClassificationMemberAndIncompleteDocumentCannotRender() {
        var d=data();var doc=CreatorReportDocument.from(d,List.of(new Topic("T1","一部分","观众反馈","",List.of("2"),2,"",List.of())),null);
        assertFalse(doc.coverage().complete());assertThrows(AgentException.class,()->CreatorAudienceReport.render(doc));
        String message=RequestMessages.execution("结合视频生成观众反馈报告",new RequestContext("1001"));
        assertEquals("audience.report",TaskMeaning.defaults().resolve(message,LocalDate.now(),Instant.now()).type());
        assertEquals("audience.report",TaskMeaning.defaults().resolve("分析视频1001的弹幕和评论",LocalDate.now(),Instant.now()).type());
        assertEquals("audience.report",TaskMeaning.defaults().resolve("分析视频1001的评论并做复盘",LocalDate.now(),Instant.now()).type());
    }
    @Test void requiredVideoCannotBeOmittedBeforeSavingAnyClassification() {
        var state=new AtomicReference<>(DanmakuClassification.empty());var context=new ReportSkillFlowTest().context(data(),state);
        when(context.reportRequiresVideo()).thenReturn(true);
        var error=assertThrows(AgentException.class,()->new CreateDanmakuReportTool(json).execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full")),context));
        assertEquals("INVALID_REPORT",error.code());verify(context,never()).saveReportClassification(anyString(),any());verify(context,never()).saveReport(anyString(),any());
    }
    @Test void emptyFeedbackPublishesAnHonestEmptyReportWithoutReadingVideo() {
        var base=new ReportSkillFlowTest().data(0);
        var data=new Dataset(base.query(),0,0,base.buckets(),List.of(),base.dataAsOf(),"fixture",false,base.audience(),new AudienceReportData.Sources(0,0,0,0,0,0,0,0,Instant.now()));
        var context=new ReportSkillFlowTest().context(data,new AtomicReference<>(DanmakuClassification.empty()));when(context.reportRequiresVideo()).thenReturn(true);
        var result=new CreateDanmakuReportTool(json).execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full")),context);
        assertNotNull(result.terminalAnswer());var doc=json.convertValue(result.data().get("report_document"),CreatorReportDocument.class);
        assertTrue(doc.coverage().complete());assertEquals(0,doc.metrics().analyzedMessages());assertTrue(doc.topics().isEmpty());
        verify(context,never()).query(any());verify(context,never()).videoQuery(any());
    }
    private Map<String,Object> topic(String key,String title,List<String> ids){return Map.of("topic_key",key,"title",title,"observation","观众表达了这一主题的关注，作者发言另行说明。","recommendation","补充对应说明。","sample_ids",ids);}
    private Map<String,Object> merge(String title,List<String> keys){return Map.of("title",title,"observation","观众表达了这一主题的关注，作者发言另行说明。","recommendation","补充对应说明。","topic_keys",keys);}
}
