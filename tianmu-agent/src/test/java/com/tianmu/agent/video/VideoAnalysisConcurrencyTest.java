package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.AccessScope;
import com.tianmu.agent.runtime.control.*;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class VideoAnalysisConcurrencyTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private VideoAnalysisService service(VideoContentPort port, VisionModelPort vision) {
        var beans = new StaticListableBeanFactory(); beans.addBean("video", port);
        return new VideoAnalysisService(new VideoModelProperties(true, "compatible", java.net.URI.create("https://vision.example"), "key", "vision", ""),
                beans.getBeanProvider(VideoContentPort.class), new VideoContentCache(beans.getBeanProvider(DataSource.class), json),
                OpenTelemetry.noop().getTracer("test"), vision, (a, c) -> fail("No audio"), new VideoAnalysisFlights());
    }
    private AccessScope owner() { return new AccessScope("tenant", "user", Set.of(7L)); }
    private VideoContentPort port(AtomicInteger checks, AtomicReference<Thread> revoke) {
        return new VideoContentPort() {
            public VideoContent.Source source(long id, AccessScope scope, CallControl c) {
                checks.incrementAndGet();
                if (Thread.currentThread() == revoke.get()) throw new AgentException("FORBIDDEN", "revoked");
                return new VideoContent.Source(id, "a".repeat(64), 1000);
            }
            public VideoContent.Media media(long id, String version, long start, long end, AccessScope scope, CallControl c) {
                return new VideoContent.Media(new VideoContent.Source(id, version, 1000), start, end, List.of(new VideoContent.Frame(500, "/9j/")), "");
            }
        };
    }
    @Test void concurrentRequestsShareOneChargedModelCallAndReauthorizeEachCaller() throws Exception {
        var checks = new AtomicInteger(); var modelCalls = new AtomicInteger(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var followerThread = new AtomicReference<Thread>();
        var service = service(port(checks, new AtomicReference<>()), (media, focus, transcript, c) ->
                ModelBudget.execute(c, "vision", true, () -> {
                    modelCalls.incrementAndGet(); entered.countDown(); VideoAnalysisFlightsTest.await(release);
                    return json.valueToTree(Map.of("summary", "visible scene", "moments", List.of(), "usage", Map.of("prompt_tokens", 17, "completion_tokens", 9)));
                }));
        var leaderBudget = new ModelBudget(100, 50); var followerBudget = new ModelBudget(100, 50);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var leader = pool.submit(() -> {
                try (var scope = Context.current().makeCurrent()) {
                    return service.analyze(7, null, null, "", owner(), VideoAnalysisFlightsTest.control().withBudget(leaderBudget));
                }
            });
            VideoAnalysisFlightsTest.await(entered);
            var follower = pool.submit(() -> {
                followerThread.set(Thread.currentThread());
                try (var scope = Context.current().makeCurrent()) {
                    return service.analyze(7, null, null, "", owner(), VideoAnalysisFlightsTest.control());
                }
            });
            VideoAnalysisFlightsTest.waiting(followerThread); release.countDown();
            assertEquals(leader.get(2, TimeUnit.SECONDS), follower.get(2, TimeUnit.SECONDS));
            assertEquals(1, modelCalls.get()); assertEquals(4, checks.get());
            assertEquals(26L, leaderBudget.attributes().get("budget.charged_tokens"));
            assertEquals(0L, followerBudget.attributes().get("budget.charged_tokens"));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void revokedWaitingCallerCannotReceiveSharedFacts() throws Exception {
        var revoke = new AtomicReference<Thread>(); var followerThread = new AtomicReference<Thread>();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var service = service(port(new AtomicInteger(), revoke), (media, focus, transcript, c) -> {
            entered.countDown(); VideoAnalysisFlightsTest.await(release);
            return json.valueToTree(Map.of("summary", "scene", "moments", List.of()));
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var leader = pool.submit(() -> service.analyze(7, null, null, "", owner(), VideoAnalysisFlightsTest.control()));
            VideoAnalysisFlightsTest.await(entered);
            var follower = pool.submit(() -> { followerThread.set(Thread.currentThread()); return service.analyze(7, null, null, "", owner(), VideoAnalysisFlightsTest.control()); });
            VideoAnalysisFlightsTest.waiting(followerThread); revoke.set(followerThread.get()); release.countDown();
            assertNotNull(leader.get(2, TimeUnit.SECONDS));
            var error = assertThrows(ExecutionException.class, () -> follower.get(2, TimeUnit.SECONDS));
            assertEquals("FORBIDDEN", ((AgentException) error.getCause()).code());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
}
