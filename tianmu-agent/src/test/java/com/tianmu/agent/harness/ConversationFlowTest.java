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
        }
    }
}
