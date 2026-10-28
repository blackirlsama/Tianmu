package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.application.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.skill.SkillCatalog;
import com.tianmu.agent.tool.*;
import com.tianmu.agent.tool.builtin.AnalyzeVideoContentTool;
import com.tianmu.agent.video.VideoAnalysisService;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskContractTest {
    private RunState prepared(String text) {
        RunState run = new RunState(new AccessScope("t", "u", Set.of(1001L, 1002L)), text);
        run.prepared(new Result(Route.HARNESS, new PreparedRequest(text, text, Emotion.UNKNOWN, Set.of()), null, null));
        run.task(new TaskAssembler(new SkillCatalog(), new PromptCatalog(), new ToolCatalog()).assemble(run));
        return run;
    }
    @Test void playbackBoundsSurvivePersistedJsonAndRejectInvalidSpecifications() throws Exception {
        for (String range : List.of("第2秒到第6秒", "2到6秒", "00:02到00:06", "第2.000秒至第6.000秒")) {
            var spec = VideoContentTask.parse("分析视频1001 " + range + "的画面");
            var json = new ObjectMapper();
            var restored = json.readValue(json.writeValueAsBytes(spec), com.tianmu.agent.core.TaskTypes.Specification.class);
            assertEquals(new TaskConditions.Video(1001, 2000L, 6000L), restored.typed());
            assertEquals(spec.type(), restored.type());
            assertEquals(spec.provenance(), restored.provenance());
            assertFalse(json.writeValueAsString(spec).contains("typed"));
        }
        for (String range : List.of("第6秒到第2秒", "第2秒到第2秒", "第-2秒到第6秒", "第2秒到第123秒",
                "第2秒", "前6秒", "1分钟到2分钟", "00:01:02到00:01:06", "第2.0001秒到第6秒", "2到6秒和8到10秒")) {
            String text = "分析视频1001 " + range + "的画面";
            assertThrows(IllegalArgumentException.class, () -> VideoContentTask.parse(text), range);
            assertTrue(new VideoContentReadiness().validate(new Input(text, LocalDate.now(), new AccessScope("t", "u", Set.of(1001L)))).isPresent(), range);
        }
        assertEquals(new TaskConditions.Video(1001, null, null), VideoContentTask.parse("分析视频1001的画面").typed());
        assertThrows(IllegalArgumentException.class, () -> new com.tianmu.agent.core.TaskTypes.Specification("video.content",
                Map.of("video_id", 1001, "start_ms", 2000), Map.of()));
    }
    @Test void boundsAndVideoAreCheckedBeforePaidWorkAndAgainOnEvidenceAdmission() {
        RunState run = prepared("分析视频1001第2秒到第6秒的画面");
        var policy = new BusinessEvidencePolicy();
        policy.checkVideoQuery(run, 1001, 2000L, 6000L);
        policy.checkVideoQuery(run, 1001, 2500L, 5500L);
        for (Long[] range : Arrays.asList(new Long[]{null, null}, new Long[]{1000L, 6000L}, new Long[]{2000L, 7000L}, new Long[]{4000L, 3000L}))
            assertEquals("TASK_CONSTRAINT_VIOLATION", assertThrows(AgentException.class,
                    () -> policy.checkVideoQuery(run, 1001, range[0], range[1])).code());
        assertEquals("TASK_CONSTRAINT_VIOLATION", assertThrows(AgentException.class,
                () -> policy.checkVideoQuery(run, 1002, 2000L, 6000L)).code());
        var facts = new VideoContent.Facts("video-content-v1", 1001, "v1", 0, 6000, "scene",
                List.of(new VideoContent.Moment(2500, "frame")), "", "SAMPLED_FRAMES", List.of(2500L), "model", Instant.now());
        assertThrows(AgentException.class, () -> policy.registerVideoEvidence(run, facts));
        assertTrue(run.execution().evidence().isEmpty());
        var video = mock(VideoAnalysisService.class);
        var context = mock(ToolContext.class);
        doAnswer(invocation -> { policy.checkVideoQuery(run, invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)); return null; })
                .when(context).checkVideoQuery(anyLong(), any(), any());
        assertThrows(AgentException.class, () -> new AnalyzeVideoContentTool(video, new ObjectMapper()).execute(
                new ToolCall("analyze_video_content", Map.of("video_id", 1001L)), context));
        verifyNoInteractions(video);
        run.cancel();
    }
    @Test void preparedTaskFreezesRegistryWhileSkillActivationCannotChangeTheConfirmedVideo() {
        try (var fixture = new HarnessLifecycleTest.Fixture()) {
            RunState general = prepared("你好");
            assertEquals(new ToolCatalog().all().keySet(),general.task().allowedTools());
            assertDoesNotThrow(() -> new BusinessEvidencePolicy().checkVideoQuery(general,1001));
            assertEquals(Set.of("list_skills","read_skill","read_result","read_history"),fixture.tools.definitions(general).stream().map(ToolDefinition::name).collect(java.util.stream.Collectors.toSet()));
            general.cancel();
            RunState run = prepared("分析视频1001第2秒到第6秒的画面");
            assertEquals(new ToolCatalog().all().keySet(),run.task().allowedTools());
            assertFalse(run.task().allowedTools().contains("future_mutation"));
            run.execution().activateSkill("video-metrics");
            assertEquals("TASK_CONSTRAINT_VIOLATION", assertThrows(AgentException.class, () -> fixture.tools.execute(
                    new ToolCall("query_video_metrics", Map.of("video_id", 1002, "date", run.businessDate.minusDays(1).toString(), "metric", "bullet_count")), run)).code());
            assertEquals(0, fixture.queries.get());
            run.cancel();
        }
    }
}
