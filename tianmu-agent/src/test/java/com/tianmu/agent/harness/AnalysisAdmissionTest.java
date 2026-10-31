package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import com.tianmu.agent.application.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.tool.builtin.CreateDanmakuReportTool;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisAdmissionTest {
    void prepare(RunState run) {
        run.start();
        run.prepared(new Result(Route.HARNESS,new PreparedRequest(run.message,run.query(),Emotion.UNKNOWN,Set.of()),null,null));
        run.task(new TaskAssembler(new SkillCatalog(),new PromptCatalog(),new ToolCatalog()).assemble(run));
    }
    VideoContent.Facts video() {
        return new VideoContent.Facts("video-content-v1",1001,"fixture",0,1000,"<鸭子>",
                List.of(new VideoContent.Moment(500,"鸭子经过")),"","SAMPLED_FRAMES",List.of(500L),"fixture",Instant.now());
    }
    @Test void excludedSourcesCannotBeDiscoveredOrDispatchedEvenAfterSkillActivation() {
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var run=new RunState(new AccessScope("t","u",Set.of(1001L)),"视频1001的评论报告，只分析评论");prepare(run);
            run.execution().activateSkill("audience-report");run.execution().activateSkill("video-content");run.execution().activateSkill("danmaku-analysis");
            var names=fixture.tools.definitions(run).stream().map(ToolDefinition::name).toList();
            assertTrue(names.contains("query_audience"));assertFalse(names.contains("query_danmaku"));assertFalse(names.contains("analyze_video_content"));
            for(String tool:List.of("analyze_video_content","query_danmaku"))
                assertEquals("TASK_CONSTRAINT_VIOLATION",assertThrows(AgentException.class,()->fixture.tools.execute(new ToolCall(tool,Map.of("video_id",1001)),run)).code());
            assertEquals(0,fixture.queries.get());assertTrue(run.execution().evidence().isEmpty());run.cancel();
        }
    }
    @Test void deliveryRequiresEverySelectedSourceAndRejectsEvidenceFromExcludedSources() {
        var spec=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve("结合视频内容分析视频1001的评论",LocalDate.now());
        var owner=new AccessScope("t","u",Set.of(1001L));var run=UUID.randomUUID();
        var v=new Evidence("video",run,owner,null,null,video());
        assertEquals("INVALID_EVIDENCE",assertThrows(AgentException.class,()->AnalysisExecutionPolicy.requireComplete(spec,List.of(v))).code());
        var comments=com.tianmu.agent.preparation.TaskMeaning.defaults().resolve("分析视频1001的评论",LocalDate.now());
        assertEquals("TASK_CONSTRAINT_VIOLATION",assertThrows(AgentException.class,()->AnalysisExecutionPolicy.requireComplete(comments,List.of(v))).code());
    }
    @Test void videoOnlyReportMustActuallyPersistBeforeToolDeliveryCanSucceed() {
        try(var fixture=new LoopControlTest.Fixture(LoopControlTest.policy(6))) {
            var repository=new MemoryRunRepository();
            var run=repository.create("生成视频1001的视频内容报告，只看视频内容",new AccessScope("t","u",Set.of(1001L)),null,null,null).run();prepare(run);
            var evidence=new BusinessEvidencePolicy().registerVideoEvidence(run,video());
            var delivery=new DeliveryService(fixture.harness);var reservation=delivery.reserve(run);
            assertEquals("REPORT_REQUIRED",assertThrows(AgentException.class,()->delivery.answer(run,new Answer("报告已生成",List.of(evidence.id())),reservation,StopReason.TOOL_DELIVERY)).code());
            assertFalse(run.execution().hasReport(evidence.id()));
            var unavailable=mock(com.tianmu.agent.runtime.store.ReportStore.class);
            when(unavailable.saveReport(eq(run.id),eq(run.owner),eq(evidence.id()),any(byte[].class))).thenThrow(new AgentException("STORAGE_UNAVAILABLE","fixture"));
            assertThrows(AgentException.class,()->new ReportArtifacts(unavailable).save(run,evidence.id(),new byte[]{1}));
            assertFalse(run.execution().hasReport(evidence.id()));
            var context=mock(ToolContext.class);when(context.evidence(evidence.id())).thenReturn(evidence);
            when(context.analysisContract()).thenReturn(run.task().specification().analysis());
            when(context.saveReport(eq(evidence.id()),any(byte[].class))).thenAnswer(call->new ReportArtifacts(repository).save(run,evidence.id(),call.getArgument(1)));
            var result=new CreateDanmakuReportTool(new ObjectMapper()).execute(new ToolCall("create_danmaku_report",Map.of("evidence_id",evidence.id())),context);
            assertTrue(run.execution().hasReport(evidence.id()));
            String html=new String(repository.report(run.id,run.owner,(String)result.data().get("artifact_id")),java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(html.contains("未读取评论和弹幕"));assertTrue(html.contains("&lt;鸭子&gt;"));
            delivery.answer(run,result.terminalAnswer(),reservation,StopReason.TOOL_DELIVERY);
            assertEquals(Status.SUCCEEDED,run.snapshot().status());
        }
    }
    @Test void explicitPlainJointAnalysisCanDeliverWithoutCreatingAnArtifact() {
        try(var fixture=new LoopControlTest.Fixture(LoopControlTest.policy(6))) {
            var run=new RunState(new AccessScope("t","u",Set.of(1001L)),"分析视频1001昨天的评论和弹幕，不需要报告");prepare(run);
            assertFalse(run.task().specification().analysis().reportRequired());
            var q=((TaskConditions.Report)run.task().specification().typed()).query();
            var bins=new ArrayList<DanmakuTypes.Bucket>();for(int i=0;i<12;i++)bins.add(new DanmakuTypes.Bucket(i,0));
            var d=new DanmakuTypes.Dataset(q,0,0,bins,List.of(),Instant.now(),"fixture",false,new AudienceData.Overview(List.of(),List.of(),0,List.of(),0,0));
            var c=new VideoComments.Dataset("video-comments-v1",q,0,0,0,List.of(),Instant.now(),"fixture");
            var data=AudienceReportData.combine(new AudienceReportData.Snapshot(d,c,0),"u");
            var evidence=new BusinessEvidencePolicy().registerDanmakuEvidence(run,data);
            var delivery=new DeliveryService(fixture.harness);
            delivery.answer(run,new Answer("该统计区间内没有保留的评论和弹幕。",List.of(evidence.id())),delivery.reserve(run),StopReason.ANSWER);
            assertEquals(Status.SUCCEEDED,run.snapshot().status());assertFalse(run.execution().hasReport(evidence.id()));
        }
    }
}
