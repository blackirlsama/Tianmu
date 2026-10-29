package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.AudienceData.*;
import com.tianmu.agent.core.DanmakuTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.application.TaskAssembler;
import com.tianmu.agent.preparation.DanmakuReportTask;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.ToolCatalog;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.store.*;
import com.tianmu.agent.runtime.events.InMemoryRunEventStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReportCheckpointTest {
    @Test void rebuildingExecutionStateResumesTheAdmittedBatchWithoutRepeatingMembership() throws Exception {
        var json=new ObjectMapper().findAndRegisterModules();var end=LocalDateTime.now(DanmakuTypes.ZONE).minusSeconds(1).withNano(0);
        var query=new Query(1001,end.minusDays(7),end);List<TextGroup> corpus=new ArrayList<>();
        for(int i=1;i<=200;i++)corpus.add(new TextGroup(Integer.toString(i),"完整原文"+i,1,1000L,false));
        var data=new Dataset(query,200,1,java.util.stream.IntStream.range(0,12).mapToObj(i->new Bucket(i,i==0?200:0)).toList(),List.of(),Instant.now(),"fixture",false,new Overview(List.of(),List.of(new Position(0,3000,200)),0,corpus,200,200));
        try(var fixture=new HarnessLifecycleTest.Fixture()) {
            var run=new RunState(new AccessScope("t","u",Set.of(1001L)),"分析视频1001弹幕并生成报告");
            run.bindControl(Duration.ofMinutes(2),12);fixture.harness.start(run);
            var prepared=new Result(Route.HARNESS,new PreparedRequest(run.message,run.message,Emotion.UNKNOWN,Set.of(),Set.of(),Intent.DANMAKU_REPORT,DanmakuReportTask.specification(query)),null,null);
            run.prepared(prepared);run.task(new TaskAssembler(new SkillCatalog(),new PromptCatalog(),new ToolCatalog()).assemble(run));run.start();
            run.execution().evidence().put(new Evidence("ev_input",run.id,run.owner,null,data));
            fixture.tools.execute(new ToolCall("read_skill",Map.of("name","danmaku-analysis")),run);
            int firstEnd=DanmakuClassification.batch(data.audience(),0).size();
            fixture.tools.execute(batch(json,0,firstEnd),run);
            var checkpoint=run.execution().evidence().snapshot().values().stream().filter(e->e.reportCheckpoint()!=null).findFirst().orElseThrow();
            assertEquals(firstEnd,checkpoint.reportCheckpoint().classification().offset());
            assertEquals(checkpoint.reportCheckpoint(),json.readValue(json.writeValueAsBytes(checkpoint.reportCheckpoint()),ReportCheckpoint.class));
            var resumed=new RunState(run.id,run.sessionId,run.turnSeq,run.owner,run.message,run.query(),run.createdAt,run.deadline(),RunBackend.MEMORY,new InMemoryRunEventStore(),run.execution().evidence(),false);
            fixture.harness.forget(run.id);resumed.bindPersistedControl(run.modelCallLimit());fixture.harness.start(resumed);
            resumed.prepared(prepared);resumed.task(run.task());resumed.start();resumed.execution().activateSkill("danmaku-analysis");
            assertEquals(run.task().deadline(),resumed.deadline());assertEquals(12,resumed.modelCallLimit());
            int nextEnd=firstEnd+DanmakuClassification.batch(data.audience(),firstEnd).size();
            var result=fixture.tools.execute(batch(json,firstEnd,nextEnd),resumed);
            assertEquals(nextEnd,((Map<?,?>)result.data().get("classification")).get("offset"));
            assertEquals(nextEnd,resumed.execution().classification("ev_input").topics().get(0).sampleIds().size());
            run.cancel();resumed.cancel();
        }
    }
    private ToolCall batch(ObjectMapper json,int start,int end)throws Exception {
        List<String> ids=new ArrayList<>();for(int i=start+1;i<=end;i++)ids.add("G"+i);
        return new ToolCall("create_danmaku_report",Map.of("evidence_id","ev_input","offset",start,"topics_json",json.writeValueAsString(List.of(Map.of("topic_key","T1","title","观众讨论","observation","观众讨论相关内容","recommendation","","sample_ids",ids)))));
    }
}
