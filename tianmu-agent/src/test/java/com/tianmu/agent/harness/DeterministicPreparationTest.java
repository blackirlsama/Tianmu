package com.tianmu.agent.harness;

import com.tianmu.agent.application.TaskAssembler;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.CallControl;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeterministicPreparationTest {
    private PreparationPolicy policy() { return new PreparationPolicy(Duration.ofMillis(200), Duration.ofSeconds(1), Duration.ofMillis(200), Duration.ofSeconds(1), true, false); }
    private RunState run(String query) { return new RunState(new AccessScope("t", "u", Set.of(1001L)), query); }
    @Test void explicitTaskSkipsRecognitionAndAssemblerReusesTheFrozenSpecification() {
        for (String query : List.of("查询视频1001昨天的弹幕数", "分析视频1001第2秒到第6秒的画面", "分析视频1001昨天的弹幕报告")) {
            try (var f = new HarnessLifecycleTest.Fixture()) {
                var model = mock(PreparationModelPort.class);
                try (var prep = new PreparationService((i,c) -> Optional.empty(), model, policy(), f.harness,
                        List.of(new MetricQueryReadiness(), new VideoContentReadiness()), TaskMeaning.defaults())) {
                    RunState run = run(query); f.harness.start(run);
                    var result = prep.prepare(run, query); run.prepared(result);
                    assertEquals(Route.HARNESS, result.route());
                    assertEquals(0, run.snapshot().preparationCalls());
                    assertTrue(result.request().skippedStages().containsAll(Set.of("intent", "understanding", "emotion", "rewrite")));
                    var meaning = mock(TaskMeaning.class);
                    var assembler = new TaskAssembler(new com.tianmu.agent.skill.SkillCatalog(), new com.tianmu.agent.prompt.PromptCatalog(), new com.tianmu.agent.tool.ToolCatalog(), meaning);
                    assertEquals(result.request().specification(), assembler.assemble(run).specification());
                    verifyNoInteractions(meaning, model);
                    run.cancel();
                }
            }
        }
    }
    @Test void missingOrUnsupportedBoundsClarifyBeforeAnyRecognitionCalls() {
        try (var f = new HarnessLifecycleTest.Fixture()) {
            var model = mock(PreparationModelPort.class);
            try (var prep = new PreparationService((i,c) -> Optional.empty(), model, policy(), f.harness,
                    List.of(new MetricQueryReadiness(), new VideoContentReadiness()))) {
                for (String query : List.of("查询视频1001从2026-10-24 12:00的弹幕数", "查询视频1001昨天和2026-10-24的弹幕数",
                        "查询视频1001和视频1002昨天的弹幕数", "分析视频1001第2秒的画面", "分析视频1001第8秒到第2秒的画面")) {
                    RunState run = run(query); f.harness.start(run);
                    assertEquals(Route.CLARIFY, prep.prepare(run, query).route());
                    assertEquals(0, run.snapshot().preparationCalls()); run.cancel();
                }
                verifyNoInteractions(model);
            }
        }
    }
    @Test void unclassifiedRequestStillUsesUnderstandingButDoesNotPayForDisabledEmotion() {
        try (var f = new HarnessLifecycleTest.Fixture(); var prep = new PreparationService((i,c) -> Optional.empty(), new PreparationFlowTest.Model() {
            public Emotion emotion(Input i, CallControl c) { fail("Emotion is disabled"); return null; }
        }, policy(), f.harness)) {
            RunState run = run("解释一下构图"); f.harness.start(run);
            var result = prep.prepare(run, run.message);
            assertEquals(Route.HARNESS, result.route());
            assertEquals(2, run.snapshot().preparationCalls());
            assertEquals(Emotion.UNKNOWN, result.request().emotion());
            assertTrue(result.request().skippedStages().contains("emotion")); run.cancel();
        }
    }
}
