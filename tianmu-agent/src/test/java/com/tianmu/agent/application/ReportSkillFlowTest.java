package com.tianmu.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.tool.ToolContext;
import com.tianmu.agent.tool.builtin.CreateDanmakuReportTool;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportSkillFlowTest {
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    Dataset data(int size){
        var end=LocalDateTime.now(DanmakuTypes.ZONE).minusSeconds(2).withNano(0);
        List<TextGroup> groups=new ArrayList<>();
        for(int i=0;i<size;i++)groups.add(new TextGroup(Long.toString(7000000000000000000L+i),"小鸭的绒毛看起来很柔软，想看更多观察片段。"+i+(i==0?"全量原文必须保留".repeat(30):""),1,(long)(i%31)*1000,false));
        var audience=new Overview(size==0?List.of():List.of(new Interaction("99",size,7,size,Instant.now(),"模拟·布丁")),size==0?List.of():List.of(new Position(0,3000,size)),0,groups,size,size);
        return new Dataset(new Query(1001,end.minusDays(7),end),size,size==0?0:1,java.util.stream.IntStream.range(0,12).mapToObj(i->new Bucket(i,i==0?size:0)).toList(),List.of(),Instant.now(),"fixture",false,audience);
    }
    ToolContext context(Dataset data,AtomicReference<DanmakuClassification> progress){
        var c=mock(ToolContext.class);var owner=new AccessScope("t","u",Set.of(1001L));
        when(c.remainingModelCalls()).thenReturn(16);
        when(c.evidence("ev_full")).thenReturn(new Evidence("ev_full",UUID.randomUUID(),owner,null,data));
        when(c.reportClassification("ev_full")).thenAnswer(i->progress.get());
        doAnswer(i->{progress.set(i.getArgument(1));return null;}).when(c).saveReportClassification(eq("ev_full"),any());
        when(c.saveReport(eq("ev_full"),any())).thenReturn(new ReportRef("report","/report"));return c;
    }
    ToolCall batch(Dataset data,DanmakuClassification progress) throws Exception {
        var groups=DanmakuClassification.batch(data.audience(),progress.offset());
        return new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","offset",progress.offset(),"topics_json",json.writeValueAsString(List.of(Map.of(
                "topic_key","T1","title","小鸭的外观和日常受到喜爱","observation","观众喜欢小鸭的绒毛，希望看到更多日常片段。","recommendation","保留清晰近景，补充同一只小鸭的后续日常。","sample_ids",groups.stream().map(TextGroup::id).toList())))));
    }
    @Test void twoHundredMessagesAreAllClassifiedBeforeOneReportIsRenderedWithoutHiddenModelCalls() throws Exception {
        var data=data(200);var progress=new AtomicReference<>(DanmakuClassification.empty());var c=context(data,progress);var tool=new CreateDanmakuReportTool(json);
        assertEquals(data.audience().corpus().get(0).text(),DanmakuClassification.batch(data.audience(),0).get(0).text());
        ToolResult result=null;int batches=0;
        while(progress.get().offset()<200){
            result=tool.execute(batch(data,progress.get()),c);batches++;
            if(progress.get().offset()<200){assertNull(result.terminalAnswer());verify(c,never()).saveReport(anyString(),any());}
        }
        assertEquals(5,batches);assertNotNull(result.terminalAnswer());assertEquals(200,progress.get().topics().get(0).messages());
        assertEquals(200,new HashSet<>(progress.get().topics().get(0).sampleIds()).size());
        var saved=org.mockito.ArgumentCaptor.forClass(byte[].class);verify(c,times(1)).saveReport(eq("ev_full"),saved.capture());
        String html=new String(saved.getValue(),StandardCharsets.UTF_8);
        assertTrue(html.contains("先看结论"));assertTrue(html.contains("已分析本时段全部 200 条弹幕"));assertTrue(html.contains("模拟·布丁"));assertTrue(html.contains("200 条相关弹幕"));
        assertFalse(html.contains("7000000000000000000"));assertFalse(html.contains("canonical"));assertFalse(html.contains("[0, 0"));assertFalse(html.contains("videoId "));
        assertFalse(html.contains("本报告包含模拟测试数据"));assertTrue(html.contains("class=\"trend-line\""));assertFalse(html.contains("word-cloud"));
        verify(c,never()).query(any());verify(c,never()).videoQuery(any());
        Path out=Path.of("target/report-preview");Files.createDirectories(out);Files.write(out.resolve("full-audience-report.html"),saved.getValue());
        Files.writeString(out.resolve("full-audience-report.json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(result.data().get("report_document")),StandardCharsets.UTF_8);
        var document=json.convertValue(result.data().get("report_document"),CreatorReportDocument.class);
        assertEquals(200,document.metrics().analyzedMessages());
        assertTrue(document.topics().get(0).quotes().size()<3,"Near-identical quotes should not be used to fill slots");
    }
    @Test void unknownMemberWrongOffsetAndPrematureRenderingDoNotPublishPartialReports() throws Exception {
        var data=data(200);var progress=new AtomicReference<>(DanmakuClassification.empty());var c=context(data,progress);var tool=new CreateDanmakuReportTool(json);
        assertThrows(AgentException.class,()->tool.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full")),c));
        var incomplete=new LinkedHashMap<>(batch(data,progress.get()).arguments());
        var nodes=json.readTree((String)incomplete.get("topics_json"));((com.fasterxml.jackson.databind.node.ArrayNode)nodes.get(0).get("sample_ids")).add("G999");incomplete.put("topics_json",nodes.toString());
        assertThrows(AgentException.class,()->tool.execute(new ToolCall("create_danmaku_report",incomplete),c));assertEquals(0,progress.get().offset());
        var skipped=new LinkedHashMap<>(batch(data,progress.get()).arguments());skipped.put("offset",80);
        assertThrows(AgentException.class,()->tool.execute(new ToolCall("create_danmaku_report",skipped),c));
        verify(c,never()).saveReport(anyString(),any());
    }
    @Test void fullTextIsNotTruncatedAndUntrustedHtmlIsEscaped() throws Exception {
        String text="前文".repeat(150)+"<script>alert('x')</script>";
        var group=new TextGroup("1",text,1,1000L,false);assertEquals(text,group.forModel().text());
        var base=data(1);var audience=new Overview(List.of(new Interaction("99",1,1,1,Instant.now(),"<img src=x>")),List.of(new Position(0,3000,1)),0,List.of(group),1,1);
        var d=new Dataset(base.query(),1,1,base.buckets(),List.of(),Instant.now(),"fixture",false,audience);
        String html=CreatorAudienceReport.render(d,List.of(new Topic("T1","<script>bad</script>","<b>观众提问</b>","具体建议",List.of("1"),1,"",List.of())),null);
        assertTrue(html.contains("&lt;script&gt;bad"));assertFalse(html.contains("<script>"));assertTrue(html.contains("&lt;img src=x&gt;"));
    }
    @Test void quotesAreDeduplicatedAcrossTopicsWithoutDroppingAnyAnalyzedMessage() throws Exception {
        var base=data(4);
        List<TextGroup> groups=List.of(new TextGroup("1","这只小鸭真可爱！",1,1000L,false),new TextGroup("2","这只小鸭真可爱",1,2000L,false),
                new TextGroup("3","希望下一次能拍一个完整的原声观察版本",1,3000L,false),new TextGroup("4","希望下一次能拍一个完整的原声观察版本呀",1,4000L,false));
        var audience=new Overview(List.of(),List.of(new Position(0,10000,4)),0,groups,4,4);
        var source=new Dataset(base.query(),4,1,base.buckets(),List.of(),Instant.now(),"fixture",false,audience);
        var topics=List.of(new Topic("T1","外观","观众喜欢小鸭","",List.of("1","3"),2,"",List.of()),new Topic("T2","日常","观众期待原声日常","",List.of("2","4"),2,"",List.of()));
        var document=CreatorReportDocument.from(source,topics,null);
        assertEquals(2,document.topics().stream().mapToInt(t->t.quotes().size()).sum());
        assertEquals(4,document.topics().stream().mapToLong(CreatorReportDocument.TopicCard::messages).sum());assertEquals(4,document.metrics().analyzedMessages());
        String encoded=json.writeValueAsString(document);assertEquals(document,json.readValue(encoded,CreatorReportDocument.class));
        assertEquals(CreatorAudienceReport.render(document),CreatorAudienceReport.render(json.readValue(encoded,CreatorReportDocument.class)));
    }
    @Test void quoteSelectionPrefersTopicRelevantOriginalsRatherThanTheShortestText(){
        var base=data(2);var groups=List.of(new TextGroup("1","哈哈",1,1000L,false),new TextGroup("2","拍摄设备和镜头参数可以放在简介里吗",1,2000L,false));
        var audience=new Overview(List.of(),List.of(new Position(0,3000,2)),0,groups,2,2);
        var source=new Dataset(base.query(),2,1,base.buckets(),List.of(),Instant.now(),"fixture",false,audience);
        var document=CreatorReportDocument.from(source,List.of(new Topic("T1","拍摄设备咨询","观众想了解拍摄设备和镜头参数","补充设备说明",List.of("1","2"),2,"",List.of())),null);
        assertEquals(List.of("拍摄设备和镜头参数可以放在简介里吗"),document.topics().get(0).quotes().stream().map(CreatorReportDocument.Quote::text).toList());
        assertEquals(2,document.metrics().analyzedMessages());
    }
    @Test void wrappedRealModelTopicsAndShortAliasesPreserveCompleteCoverage() throws Exception {
        var data=data(2);var progress=new AtomicReference<>(DanmakuClassification.empty());var c=context(data,progress);var tool=new CreateDanmakuReportTool(json);
        String wrapped=json.writeValueAsString(Map.of("topics",List.of(Map.of("topic_key","T1","title","小鸭外观","observation","观众喜欢绒毛","recommendation","保留近景","sample_ids",List.of("G1","G2")))));
        var result=tool.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","offset",0,"topics_json",wrapped)),c);
        assertNotNull(result.terminalAnswer());assertEquals(data.audience().corpus().stream().map(TextGroup::id).toList(),progress.get().topics().get(0).sampleIds());
    }
    @Test void missingAndRepeatedAliasesStillPreserveCompleteCoverageWithoutRetry() throws Exception {
        var data=data(2);var progress=new AtomicReference<>(DanmakuClassification.empty());var c=context(data,progress);var tool=new CreateDanmakuReportTool(json);
        String topics=json.writeValueAsString(Map.of("topics",List.of(Map.of("topic_key","T1","title","小鸭外观","observation","观众喜欢绒毛","recommendation","保留近景","sample_ids",List.of("G1","G1")))));
        var result=tool.execute(new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_full","offset",0,"topics_json",topics)),c);
        assertNotNull(result.terminalAnswer());assertEquals(2,progress.get().offset());
        assertEquals(2,progress.get().topics().stream().mapToLong(Topic::messages).sum());
        assertEquals(1,progress.get().topics().stream().filter(t->t.title().equals("其他讨论")).count());
        assertEquals(2,progress.get().topics().stream().flatMap(t->t.sampleIds().stream()).distinct().count());
    }
    @Test void videoEvidenceMustActuallyBeConnectedToAReportTopicBeforeDraftCommit() throws Exception {
        var data=data(1);var progress=new AtomicReference<>(DanmakuClassification.empty());var c=context(data,progress);var tool=new CreateDanmakuReportTool(json);
        var video=new VideoContent.Facts("video-content-v1",1001,"v",0,5000,"黄色小鸭",List.of(new VideoContent.Moment(2500,"小鸭绒毛清晰可见")),"","SAMPLED_FRAMES",List.of(2500L),"vision",Instant.now());
        var source=c.evidence("ev_full");when(c.evidence("ev_video")).thenReturn(new Evidence("ev_video",source.runId(),source.owner(),null,null,video));
        var topic=new LinkedHashMap<String,Object>(Map.of("topic_key","T1","title","小鸭外观","observation","观众喜欢绒毛","recommendation","保留近景","sample_ids",List.of("G1")));
        var args=new LinkedHashMap<String,Object>(Map.of("evidence_id","ev_full","offset",0,"video_evidence_id","ev_video","topics_json",json.writeValueAsString(Map.of("topics",List.of(topic)))));
        var error=assertThrows(AgentException.class,()->tool.execute(new ToolCall("create_danmaku_report",args),c));assertTrue(error.getMessage().contains("video_context"));assertEquals(0,progress.get().offset());
        topic.put("video_context","画面能看清小鸭绒毛，与观众的外观反馈相关。");topic.put("video_at_ms",List.of(2500));args.put("topics_json",json.writeValueAsString(Map.of("topics",List.of(topic))));
        assertNotNull(tool.execute(new ToolCall("create_danmaku_report",args),c).terminalAnswer());
    }
    @Test void chartsAreScriptFreeAfterWordCloudRemoval(){
        var base=data(2);var topic=new Topic("T1","小鸭外观","观众喜欢绒毛","保留近景",base.audience().corpus().stream().map(TextGroup::id).toList(),2,"",List.of());
        String html=CreatorAudienceReport.render(base,List.of(topic),null);
        assertEquals(2,html.split("<polyline class=\"trend-line\"",-1).length-1);assertFalse(html.contains("<script"));assertFalse(html.contains("NaN"));assertFalse(html.contains("Infinity"));
        String empty=CreatorAudienceReport.render(data(0),List.of(),null);assertFalse(empty.contains("NaN"));assertFalse(empty.contains("Infinity"));
    }
}
