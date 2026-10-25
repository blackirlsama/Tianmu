package com.tianmu.agent.harness;

import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.core.RequestContext;
import com.tianmu.agent.adapters.storage.MemoryRunRepository;
import com.tianmu.agent.faq.ClasspathFaqPort;
import com.tianmu.agent.preparation.*;
import com.tianmu.agent.runtime.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ConversationFlowTest {
    @Test void videoPresetsInheritPageTargetWithoutChangingOrdinaryChatOrExplicitTargets() {
        var page = new RequestContext("1001");
        for (String text : List.of("请分析这个视频的画面内容，概括主要内容，并给出关键画面的播放时间点。", "请只分析这个视频第1秒到第4秒的画面内容，说明这段出现了什么。")) {
            String execution = com.tianmu.agent.application.RequestMessages.execution(text,page);
            assertTrue(execution.endsWith("当前视频：视频1001"));
            assertEquals("video.content",TaskMeaning.defaults().resolve(execution,java.time.LocalDate.now()).type());
            assertEquals(execution,com.tianmu.agent.application.RequestMessages.execution(execution,page));
        }
        for (String text : List.of("你能做什么？", "给我一些提升观众互动意愿的通用运营建议。", "帮我写三句自然的互动引导文案，不要太像广告。", "请分析视频1002的画面内容。"))
            assertEquals(text,com.tianmu.agent.application.RequestMessages.execution(text,page));
    }
    @Test void ordinaryConversationCanAnswerWithoutBusinessEvidenceOrTools() {
        for (String question : List.of("帮我想一句直播开场白", "如何提高直播间的弹幕互动？给我三条通用建议。", "怎么分析弹幕？")) {
        try (var fixture = new HarnessLifecycleTest.Fixture();
             var prep = new PreparationService((input, control) -> Optional.empty(), new PreparationFlowTest.Model(),
                     new PreparationPolicy(Duration.ofMillis(200), Duration.ofMillis(400)), fixture.harness,
                     List.of(new MetricQueryReadiness(), new DanmakuReportSpecificationResolver()))) {
            var run = new RunState(new AccessScope("demo", "alice", Set.of()), question);
            var runtime = new AgentRuntime(context -> {
                assertEquals("conversation", context.task().specification().type());
                assertTrue(context.availableTools().isEmpty());
                return Decision.answer("欢迎来到直播间，今天一起发现一些实用的小惊喜。", List.of());
            }, fixture.tools, fixture.harness);
            new RequestOrchestrator(prep, runtime, fixture.harness).execute(run);
            assertEquals(Status.SUCCEEDED, run.snapshot().status());
            assertTrue(run.snapshot().answer().evidenceIds().isEmpty());
            assertEquals(0, fixture.queries.get());
        }
        }
    }
    @Test void dataQuestionsStillCannotDeliverUnprovenNumbers() {
        try (var fixture = new HarnessLifecycleTest.Fixture();
             var prep = new PreparationService((input, control) -> Optional.empty(), new PreparationFlowTest.Model(),
                     new PreparationPolicy(Duration.ofMillis(200), Duration.ofMillis(400)), fixture.harness)) {
            var repo = new MemoryRunRepository();
            var run = repo.create("昨天有多少条弹幕？", new AccessScope("demo", "alice", Set.of(1001L)), null, null, null, new RequestContext("1001")).run();
            var runtime = new AgentRuntime(context -> {
                assertEquals("video.metric", context.task().specification().type());
                return Decision.answer("有999条弹幕。", List.of());
            }, fixture.tools, fixture.harness);
            new RequestOrchestrator(prep, runtime, fixture.harness).execute(run);
            assertEquals(Status.FAILED, run.snapshot().status());
            assertEquals("INVALID_ANSWER", run.snapshot().errorCode());
            assertNull(run.snapshot().answer());
        }
    }
    @Test void capabilitiesWithPageContextUseFaqWithoutAnyModelCall() {
        try (var fixture = new HarnessLifecycleTest.Fixture();
             var prep = new PreparationService(new ClasspathFaqPort(new ClassPathResource("faq/business.json"), null),
                     new PreparationFlowTest.Model() {
                         public Intent intent(Input i, com.tianmu.agent.runtime.control.CallControl c) { fail("FAQ must bypass preparation model"); return null; }
                     }, new PreparationPolicy(Duration.ofMillis(200), Duration.ofMillis(400)), fixture.harness)) {
            var run = new MemoryRunRepository().create("你能做什么", new AccessScope("demo", "alice", Set.of()), null, null, null, new RequestContext("1001")).run();
            new RequestOrchestrator(prep, new AgentRuntime(context -> { fail("FAQ must bypass harness model"); return null; }, fixture.tools, fixture.harness), fixture.harness).execute(run);
            assertEquals(Status.SUCCEEDED, run.snapshot().status());
            assertEquals(Route.FAQ, run.snapshot().route());
            assertEquals(0, run.snapshot().totalModelCalls());
            assertTrue(run.snapshot().answer().text().contains("话术"));
            assertTrue(run.snapshot().answer().text().contains("画面内容"));
            assertTrue(run.snapshot().answer().text().contains("指定片段"));
        }
    }
}
