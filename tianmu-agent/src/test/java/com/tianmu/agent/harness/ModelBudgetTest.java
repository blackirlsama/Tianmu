package com.tianmu.agent.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.harness.trace.TraceUsage;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.control.*;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelBudgetTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static CallControl control() { return new CallControl(Instant.now().plusSeconds(10), new CancellationToken()); }
    private static JsonNode usage(long in, long out) {
        return JSON.valueToTree(Map.of("usage", Map.of("prompt_tokens", in, "completion_tokens", out)));
    }
    @Test void settlesAndCountsUsageBeforeRejectingOverage() {
        var budget = new ModelBudget(100, 80);
        var trace = new TraceUsage();
        try (var scope = Context.current().with(TraceUsage.KEY, trace).makeCurrent()) {
            ModelBudget.execute(control().withBudget(budget), "text", false, () -> usage(10, 5));
            assertEquals(15L, budget.attributes().get("budget.charged_tokens"));
            var error = assertThrows(AgentException.class, () -> ModelBudget.execute(control().withBudget(budget), "vision", true, () -> usage(80, 30)));
            assertEquals("MODEL_TOKEN_BUDGET_EXCEEDED", error.code());
            assertEquals(125L, trace.totals().get("usage.total_tokens"));
            assertThrows(AgentException.class, () -> ModelBudget.execute(control().withBudget(budget), "text", false, () -> fail("Must not dispatch")));
        }
    }
    @Test void partialUsageAndTransportFailuresKeepReservation() {
        var budget = new ModelBudget(160, 80);
        try (var scope = Context.current().makeCurrent()) {
            ModelBudget.execute(control().withBudget(budget), "asr", true, () -> JSON.valueToTree(Map.of("usage", Map.of("prompt_tokens", 1))));
            assertThrows(IllegalStateException.class, () -> ModelBudget.execute(control().withBudget(budget), "text", false, () -> { throw new IllegalStateException(); }));
            assertEquals(160L, budget.attributes().get("budget.charged_tokens"));
            assertEquals(2L, budget.attributes().get("budget.unsettled_calls"));
            assertThrows(AgentException.class, () -> ModelBudget.execute(control().withBudget(budget), "text", false, () -> fail("Must not dispatch")));
        }
    }
    @Test void reservationsAreAtomicAcrossParallelBranches() throws Exception {
        var budget = new ModelBudget(80, 80);
        Context context = Context.current();
        var calls = new AtomicInteger();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> {
                try (var scope = context.makeCurrent()) {
                    start.await();
                    try { ModelBudget.execute(control().withBudget(budget), "text", false, () -> { calls.incrementAndGet(); return JSON.createObjectNode(); }); }
                    catch (AgentException e) { assertEquals("MODEL_TOKEN_BUDGET_EXCEEDED", e.code()); }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
        } finally { pool.shutdownNow(); }
    }
    @Test void externalCallsShareRunCallLimitAndSurviveSnapshotRestore() {
        var owner = new AccessScope("t", "u", Set.of(1L), "auth");
        var run = new RunState(owner, "question");
        run.bindControl(Duration.ofSeconds(30), 2);
        run.preparationCallStarted();
        var budget = new ModelBudget(100, 50);
        try (var scope = Context.current().makeCurrent()) {
            ModelBudget.execute(new CallControl(control().deadline(), new CancellationToken(), budget, run::externalModelCallStarted), "vision", true, () -> usage(1, 1));
            assertEquals(2, run.snapshot().totalModelCalls());
            assertThrows(AgentException.class, run::modelCallStarted);
            var restored = new RunState(owner, "question");
            restored.restore(run.snapshot());
            assertEquals(2, restored.snapshot().totalModelCalls());
            var error = assertThrows(AgentException.class, () -> ModelBudget.execute(new CallControl(control().deadline(), new CancellationToken(), budget, run::externalModelCallStarted), "asr", true, () -> fail("Must not dispatch")));
            assertEquals("MODEL_CALL_BUDGET_EXCEEDED", error.code());
            assertEquals(2L, budget.attributes().get("budget.charged_tokens"), "Rejected dispatch must not reserve tokens");
        }
    }
    @Test void cancellationPreventsDispatchAndReservation() {
        var budget = new ModelBudget(100, 50);
        var c = control();
        c.cancellation().cancel(com.tianmu.agent.core.StopReason.USER_CANCELLED);
        try (var scope = Context.current().makeCurrent()) {
            assertThrows(AgentException.class, () -> ModelBudget.execute(c.withBudget(budget), "text", false, () -> fail("Must not dispatch")));
            assertEquals(0L, budget.attributes().get("budget.charged_tokens"));
        }
    }
    @Test void harnessPropagatesBudgetWithoutTracingAndAcrossCallWorkers() {
        var tracer = io.opentelemetry.api.OpenTelemetry.noop().getTracer("test");
        var policy = new LoopPolicy(6, Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(3), 3, true, 2, 10, 20);
        try (var control = new LoopControl(policy);
             var harness = new RuntimeHarness(tracer, new com.tianmu.agent.harness.hook.HookDispatcher(List.of(), tracer),
                     new com.tianmu.agent.harness.hook.TransformDispatcher(List.of(), tracer, JSON), control)) {
            var run = new RunState(new AccessScope("t", "u", Set.of(1L)), "question");
            var error = assertThrows(AgentException.class, () -> harness.stage(run, "agent.model", null, null, Map.of(),
                    () -> harness.modelCall(run, false, c -> ModelBudget.execute(c, "text", false, () -> fail("Must not dispatch")))));
            assertEquals("MODEL_TOKEN_BUDGET_EXCEEDED", error.code());
            run.fail(error.code());
            assertEquals(com.tianmu.agent.core.StopReason.MODEL_TOKEN_BUDGET_EXCEEDED, run.snapshot().stopReason());
        }
    }
}
