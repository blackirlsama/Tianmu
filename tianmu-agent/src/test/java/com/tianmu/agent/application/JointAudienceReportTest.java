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
    @Test void lastBatchPublishesWithoutAnotherModelMergeAndPreservesCoverage() throws Exception {
        var data=data();var state=new AtomicReference<>(DanmakuClassification.empty());var context=new ReportSkillFlowTest().context(data,state);
        var tool=new CreateDanmakuReportTool(json);List<Map<String,Object>> topics=new ArrayList<>();
        topics.add(topic("T1","清晰近景",List.of("G2")));topics.add(topic("T7","作者补充",List.of("G1","G4")));
        topics.add(topic("T8","设备问题",List.of("G3")));topics.add(topic("T9","照料问题",List.of("G5")));
        var result=tool.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","offset",0,"topics_json",json.writeValueAsString(topics))),context);
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
        ToolResult result=null;
        while(state.get().offset()<8){
            int offset=state.get().offset();var batch=DanmakuClassification.batch(overview,offset);List<Map<String,Object>> topics=new ArrayList<>();
            for(int i=0;i<batch.size();i++)topics.add(topic("T"+(offset+i+1),"讨论主题"+(offset+i+1),List.of("G"+(offset+i+1))));
            result=tool.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","offset",offset,"topics_json",json.writeValueAsString(topics))),context);
        }
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
    @Test void allTwoHundredDanmakuAndFortyCommentsFinishInSixBatchesWithoutMergeCalls() throws Exception {
        var fixture=new ReportSkillFlowTest();var d=fixture.data(200);
        var at=d.query().start().plusHours(1).atZone(DanmakuTypes.ZONE).toInstant();
        List<VideoComments.Comment> rows=new ArrayList<>();
        for(int i=0;i<40;i++)rows.add(new VideoComments.Comment(Integer.toString(100+i),null,"882","观众","想看小鸭的后续日常 "+i,at,true,false,false));
        var comments=new VideoComments.Dataset("video-comments-v1",d.query(),40,40,1,rows,Instant.now(),"fixture");
        var combined=AudienceReportData.combine(new AudienceReportData.Snapshot(d,comments,2),"881");
        var state=new AtomicReference<>(DanmakuClassification.empty());var context=fixture.context(combined,state);
        var tool=new CreateDanmakuReportTool(json);ToolResult result=null;int calls=0;
        while(state.get().offset()<combined.audience().corpus().size()){
            result=tool.execute(fixture.batch(combined,state.get()),context);calls++;
        }
        assertEquals(6,calls);assertTrue(state.get().merged());assertNotNull(result.terminalAnswer());
        var doc=json.convertValue(result.data().get("report_document"),CreatorReportDocument.class);
        assertEquals(200,doc.sources().danmaku());assertEquals(40,doc.sources().comments());
        assertEquals(240,doc.metrics().analyzedMessages());assertTrue(doc.coverage().complete());
        assertEquals(200,doc.topics().get(0).danmaku());assertEquals(40,doc.topics().get(0).comments());
        verify(context,times(1)).saveReport(anyString(),any());
        verify(context,never()).query(any());verify(context,never()).videoQuery(any());
    }
    @Test void parentQuoteIsNotRepeatedInReplyContextAndEmptyChartsAreHidden(){
        var base=new ReportSkillFlowTest().data(0);var q=base.query();
        var at=q.start().plusHours(1).atZone(DanmakuTypes.ZONE).toInstant();
        String parent="有点好奇它当时在看什么，镜头外是不是有人拿着吃的。";
        var rows=List.of(new VideoComments.Comment("10",null,"882","甲",parent,at,true,false,false),
                new VideoComments.Comment("11","10","883","乙","我也觉得像是在看镜头外的人，不一定是吃的。",at,true,false,false));
        var comments=new VideoComments.Dataset("video-comments-v1",q,2,2,2,rows,Instant.now(),"fixture");
        var combined=AudienceReportData.combine(new AudienceReportData.Snapshot(base,comments,2),"881");
        var topic=new Topic("T1","镜头外猜测","观众猜测镜头外有什么","",List.of("1","2"),2,"",List.of());
        var doc=CreatorReportDocument.from(combined,List.of(topic),null);String html=CreatorAudienceReport.render(doc);
        assertEquals(1,html.split(java.util.regex.Pattern.quote(parent),-1).length-1);
        assertTrue(html.contains("原话见话题"));assertTrue(html.contains("本统计时段内没有弹幕，不代表视频没有弹幕"));
        assertFalse(html.contains("弹幕互动最活跃的观众"));assertFalse(html.contains("playback-title"));
        assertTrue(html.contains("activity-title"));assertEquals(2,doc.metrics().analyzedMessages());
        var out=Path.of("target/report-preview");try{Files.createDirectories(out);Files.writeString(out.resolve("comments-only-report.html"),html);}catch(java.io.IOException e){throw new RuntimeException(e);}
    }
    @Test void laterBatchKeepsEarlierVideoAnchorsAndNeedsNoModelBudgetForPublication() throws Exception {
        var fixture=new ReportSkillFlowTest();var base=fixture.data(41);
        var data=new Dataset(base.query(),41,1,base.buckets(),List.of(),base.dataAsOf(),"fixture",false,base.audience(),
                new AudienceReportData.Sources(41,0,1,0,0,0,0,1,base.dataAsOf()));
        var state=new AtomicReference<>(DanmakuClassification.empty());var context=fixture.context(data,state);
        var video=new VideoContent.Facts("video-content-v1",1001,"v",0,31000,"小鸭在草地上活动",
                List.of(new VideoContent.Moment(1000,"小鸭坐着"),new VideoContent.Moment(2500,"小鸭抬头")),"","SAMPLED_FRAMES",List.of(1000L,2500L),"fixture",Instant.now());
        var evidence=context.evidence("ev_full");when(context.evidence("ev_video")).thenReturn(new Evidence("ev_video",evidence.runId(),evidence.owner(),null,null,video));
        var tool=new CreateDanmakuReportTool(json);ToolResult result=null;
        for(int step=0;step<2;step++){
            var args=new LinkedHashMap<>(fixture.batch(data,state.get()).arguments());args.put("video_evidence_id","ev_video");
            var nodes=json.readTree((String)args.get("topics_json"));var topic=(com.fasterxml.jackson.databind.node.ObjectNode)nodes.get(0);
            topic.put("video_context",step==0?"画面中小鸭坐着。":"后续画面中小鸭抬头。");topic.putArray("video_at_ms").add(step==0?1000:2500);
            args.put("topics_json",nodes.toString());if(step==1)when(context.remainingModelCalls()).thenReturn(0);
            result=tool.execute(new ToolCall("create_danmaku_report",args),context);
        }
        assertNotNull(result.terminalAnswer());assertEquals(List.of(1000L,2500L),state.get().topics().get(0).videoAtMs());
        assertTrue(state.get().topics().get(0).videoContext().contains("小鸭坐着"));assertTrue(state.get().topics().get(0).videoContext().contains("小鸭抬头"));
        var doc=json.convertValue(result.data().get("report_document"),CreatorReportDocument.class);
        assertEquals(2,doc.topics().get(0).videoMoments().size());assertTrue(CreatorAudienceReport.render(doc).contains("配乐与收音相关评价仅来自观众表达"));
        verify(context,times(1)).saveReport(anyString(),any());
    }
    @Test void codeCompressesFifteenHundredIdenticalCommentsBeforeOneClassification() throws Exception {
        var end=LocalDateTime.now(DanmakuTypes.ZONE).minusSeconds(1).withNano(0);var first=end.minusDays(120);
        var query=new Query(1001,DanmakuTypes.ALL_TIME_START,end);query.checkTime(Instant.now());
        List<TextGroup> groups=new ArrayList<>();for(int i=1;i<=1500;i++)groups.add(new TextGroup(Integer.toString(i),"想看小鸭的后续日常",1,null,false,List.of(),"COMMENT",Integer.toString(10000+i),"",false));
        var overview=new Overview(List.of(),List.of(),1500,groups,1500,1500,first);
        var data=new Dataset(query,1500,1,java.util.stream.IntStream.range(0,12).mapToObj(i->new Bucket(i,i==0?1500:0)).toList(),List.of(),Instant.now(),"fixture",false,overview,
                new AudienceReportData.Sources(0,0,0,1500,0,1,0,1,Instant.now()));
        var empty=DanmakuClassification.empty();assertEquals(1,DanmakuClassification.remainingBatches(overview,0));
        var visible=json.valueToTree(empty.view(overview)).path("groups");assertEquals(1,visible.size());
        assertEquals(1500,visible.get(0).path("count").asInt());assertEquals(1500,visible.get(0).path("original_records").asInt());assertFalse(visible.get(0).has("original_ids"));
        assertEquals(1500,DanmakuClassification.inputs(overview,0).get(0).members().size());
        var state=new AtomicReference<>(empty);var context=new ReportSkillFlowTest().context(data,state);
        var result=new CreateDanmakuReportTool(json).execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","offset",0,"topics_json",json.writeValueAsString(List.of(topic("T1","后续日常",List.of("G1")))))),context);
        assertNotNull(result.terminalAnswer());assertEquals(1500,state.get().offset());assertEquals(1500,state.get().topics().get(0).sampleIds().size());
        var doc=json.convertValue(result.data().get("report_document"),CreatorReportDocument.class);
        assertTrue(doc.coverage().complete());assertTrue(doc.allTime());assertTrue(doc.period().startsWith("全量"));assertFalse(doc.activity().get(0).label().contains("1970"));
        assertEquals(1500,doc.topics().get(0).comments());verify(context,times(1)).saveReport(anyString(),any());
    }
    @Test void duplicateCompressionNeverMergesDifferentReplyContextsOrAuthorRoles(){
        var groups=List.of(new TextGroup("1","同意",1,null,false,List.of(),"COMMENT","101","回复设备问题",false),
                new TextGroup("2","同意",1,null,false,List.of(),"COMMENT","102","回复剪辑问题",false),
                new TextGroup("3","同意",1,null,false,List.of(),"COMMENT","103","回复设备问题",true));
        var overview=new Overview(List.of(),List.of(),3,groups,3,3);
        assertEquals(3,DanmakuClassification.inputs(overview,0).size());
    }
    @Test void emptyFullHistoryDoesNotClaimThatOtherDatesWereExcluded(){
        var base=new ReportSkillFlowTest().data(0);var query=new Query(1001,DanmakuTypes.ALL_TIME_START,base.query().end());
        var overview=new Overview(List.of(),List.of(),0,List.of(),0,0,query.end().minusDays(1));
        var data=new Dataset(query,0,0,base.buckets(),List.of(),Instant.now(),"fixture",false,overview,
                new AudienceReportData.Sources(0,0,0,0,0,0,0,0,Instant.now()));
        String html=CreatorAudienceReport.render(CreatorReportDocument.from(data,List.of(),null));
        assertTrue(html.contains("当前视频没有已保留的弹幕记录"));assertFalse(html.contains("其他日期的弹幕未计入"));
    }
    private Map<String,Object> topic(String key,String title,List<String> ids){return Map.of("topic_key",key,"title",title,"observation","观众表达了这一主题的关注，作者发言另行说明。","recommendation","补充对应说明。","sample_ids",ids);}
}
