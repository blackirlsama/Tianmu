package com.tianmu.agent.evaluation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.query.BusinessQueryPort;
import com.tianmu.agent.runtime.control.CallControl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs the bound engineering slice, never an implicit paid-model evaluation. */
@ActiveProfiles("demo")
@SpringBootTest(properties = {"spring.profiles.active=demo", "agent.storage.mode=memory", "agent.video.enabled=false",
        "harness.trace.sink=file", "harness.trace.directory=./target/evaluation/traces", "harness.trace.details.enabled=false"})
@AutoConfigureMockMvc
@Import(EvaluationBaselineTest.FixtureConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EvaluationBaselineTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Path MANIFEST = Path.of("eval/engineering-cases.json");
    private static final Path INVENTORY = Path.of("eval/scenarios.json");
    private static final JsonNode FIXTURE = fixture();
    private static final LocalDate ANCHOR = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private final List<Map<String, Object>> results = new ArrayList<>();
    @Autowired MockMvc http;
    @Autowired FixtureQuery query;
    @Autowired Environment environment;
    @Autowired com.tianmu.agent.access.IdentityPort identity;

    private static JsonNode fixture() {
        try { return JSON.readTree(MANIFEST.toFile()).path("fixture"); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    static Stream<JsonNode> cases() throws Exception {
        var manifest = JSON.readTree(MANIFEST.toFile());
        var ids = new HashSet<String>();
        JSON.readTree(INVENTORY.toFile()).path("cases").forEach(c -> ids.add(c.path("id").asText()));
        List<JsonNode> cases = new ArrayList<>(); var bound = new HashSet<String>();
        for (JsonNode item : manifest.path("cases")) {
            String id = item.path("scenario_id").asText();
            assertTrue(ids.contains(id), "Unknown scenario binding: " + id);
            assertTrue(bound.add(id), "Duplicate scenario binding: " + id);
            assertFalse(item.path("coverage").isEmpty()); assertFalse(item.path("not_assessed").isEmpty());
            cases.add(item);
        }
        assertEquals(7, cases.size()); return cases.stream();
    }

    @BeforeAll void offlineOnly() {
        var profiles = Set.of(environment.getActiveProfiles());
        assertEquals(Set.of("demo"), profiles, "Baseline must not enable model or business adapters");
        for (String user : List.of("alice", "bob")) {
            var expected = new HashSet<Long>(); FIXTURE.path("users").path(user).forEach(id -> expected.add(id.asLong()));
            assertEquals(expected, identity.authenticate("demo-" + user).videoIds());
        }
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("cases")
    void evaluate(JsonNode item) throws Exception {
        String message = item.path("message").asText().replace("{completed_date}", ANCHOR.minusDays(1).toString())
                .replace("{empty_date}", ANCHOR.minusDays(2).toString());
        ObjectNode input = JSON.createObjectNode().put("message", message);
        if (item.has("context")) input.set("context", item.path("context"));
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("scenario_id", item.path("scenario_id").asText());
        record.put("assessment_scope", "engineering_fixture_only"); record.put("input", input);
        record.put("coverage", item.path("coverage")); record.put("not_assessed", item.path("not_assessed"));
        long start = System.nanoTime(); int queriesBefore = query.calls.get();
        FixtureQuery.Gate gate = item.path("mode").asText().equals("cancel_inflight") ? query.arm() : null;
        try {
            JsonNode created = submit(input, "alice"); String id = created.path("runId").asText();
            JsonNode run;
            if (gate != null) {
                assertTrue(gate.started.await(5, TimeUnit.SECONDS), "Query did not reach the controlled cancellation point");
                http.perform(post("/api/agent/runs/" + id + "/cancel").header("Authorization", "Bearer demo-alice")).andExpect(status().isOk());
                assertTrue(gate.finished.await(5, TimeUnit.SECONDS), "Cancelled fixture query did not exit");
            }
            run = terminal(id, "alice");
            switch (item.path("mode").asText()) {
                case "metric" -> verifyMetric(run, id, item.path("expected_value").asLong(), "alice");
                case "clarification" -> {
                    assertEquals("NEEDS_INPUT", run.path("status").asText());
                    assertEquals("CLARIFY", run.path("route").asText());
                    String text = run.path("clarification").asText();
                    assertTrue(text.contains("日期")); assertFalse(text.toLowerCase(Locale.ROOT).contains("videoid"));
                    assertFalse(text.contains("视频ID")); assertTrue(run.path("answer").isNull());
                    assertEquals(queriesBefore, query.calls.get());
                }
                case "forbidden" -> {
                    assertEquals("FAILED", run.path("status").asText()); assertEquals("FORBIDDEN", run.path("errorCode").asText());
                    assertTrue(run.path("answer").isNull()); assertEquals(queriesBefore, query.calls.get());
                }
                case "cross_run" -> {
                    verifyMetric(run, id, 128, "alice");
                    http.perform(get("/api/agent/runs/" + id).header("Authorization", "Bearer demo-bob")).andExpect(status().isNotFound());
                    String bobId = submit(JSON.createObjectNode().put("message", "查询视频1002昨天的弹幕数"), "bob").path("runId").asText();
                    assertEquals("SUCCEEDED", terminal(bobId, "bob").path("status").asText());
                    String foreign = run.path("answer").path("evidenceIds").get(0).asText();
                    http.perform(get("/api/agent/runs/" + bobId + "/evidence/" + foreign).header("Authorization", "Bearer demo-bob")).andExpect(status().isNotFound());
                }
                case "cancel_inflight" -> {
                    assertEquals("CANCELLED", run.path("status").asText()); assertEquals("USER_CANCELLED", run.path("stopReason").asText());
                    assertTrue(run.path("answer").isNull());
                    assertEquals("CANCELLED", terminal(id, "alice").path("status").asText());
                }
                default -> fail("Unimplemented binding mode");
            }
            assertEquals(message, run.path("preparation").path("originalQuery").asText());
            String stream = events(id);
            assertEquals(1, stream.split("event:DONE", -1).length - 1, "Exactly one DONE required");
            record.put("status", "PASS"); record.put("terminal_status", run.path("status").asText());
            record.put("route", run.path("route").asText()); record.put("total_model_calls", run.path("totalModelCalls").asInt());
            record.put("trace_id", run.path("traceId").asText()); record.put("task", run.path("task"));
            record.put("run_id", id); record.put("model", "scripted-demo");
            record.put("provider_usage_state", "NOT_APPLICABLE_SCRIPTED_MODEL");
        } catch (AssertionError | Exception error) {
            record.put("status", "FAIL"); record.put("failure_type", error.getClass().getSimpleName()); throw error;
        } finally {
            if (gate != null) gate.release.countDown();
            record.put("elapsed_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)); results.add(record);
        }
    }

    private JsonNode submit(JsonNode input, String user) throws Exception {
        return JSON.readTree(http.perform(post("/api/agent/runs").header("Authorization", "Bearer demo-" + user)
                .contentType("application/json;charset=UTF-8").content(JSON.writeValueAsBytes(input))).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsByteArray());
    }
    private JsonNode terminal(String id, String user) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            JsonNode run = JSON.readTree(http.perform(get("/api/agent/runs/" + id).header("Authorization", "Bearer demo-" + user))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            if (!Set.of("QUEUED", "RUNNING").contains(run.path("status").asText())) return run;
            assertTrue(System.nanoTime() < end, "Run did not terminate"); Thread.sleep(15);
        }
    }
    private void verifyMetric(JsonNode run, String id, long value, String user) throws Exception {
        assertEquals("SUCCEEDED", run.path("status").asText());
        assertTrue(run.path("answer").path("text").asText().contains("模拟"));
        assertTrue(run.path("answer").path("text").asText().matches("(?s).*弹幕数为\\s*" + value + "\\s*条.*"));
        assertFalse(run.path("answer").path("evidenceIds").isEmpty());
        for (JsonNode evidenceId : run.path("answer").path("evidenceIds")) {
            JsonNode evidence = JSON.readTree(http.perform(get("/api/agent/runs/" + id + "/evidence/" + evidenceId.asText())
                    .header("Authorization", "Bearer demo-" + user)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertEquals(id, evidence.path("runId").asText()); assertEquals(user, evidence.path("owner").path("userId").asText());
            assertEquals(value, evidence.path("data").path("value").asLong());
            assertEquals(run.path("task").path("specification").path("conditions").path("date"), evidence.path("data").path("date"));
            assertEquals(run.path("task").path("specification").path("conditions").path("video_id").asLong(), evidence.path("data").path("videoId").asLong());
            assertEquals(FIXTURE.path("metric").asText(), evidence.path("data").path("metric").asText());
            assertTrue(evidence.path("data").path("complete").asBoolean()); assertEquals("Asia/Shanghai", evidence.path("data").path("timezone").asText());
            assertEquals("evaluation-synthetic-v1", evidence.path("data").path("source").asText()); assertTrue(evidence.path("data").path("demo").asBoolean());
        }
    }
    private String events(String id) throws Exception {
        MvcResult result = http.perform(get("/api/agent/runs/" + id + "/events").header("Authorization", "Bearer demo-alice"))
                .andExpect(status().isOk()).andReturn();
        if (result.getRequest().isAsyncStarted()) result.getAsyncResult(5000);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @AfterAll void saveReport() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("suite_version", "1.0.0"); report.put("generated_at", Instant.now().toString());
        report.put("assessment_scope", "engineering_fixture_only"); report.put("calendar_anchor", ANCHOR.toString());
        report.put("paid_model_calls", 0); report.put("semantic_quality", "NOT_ASSESSED");
        report.put("java_version", System.getProperty("java.version")); report.put("os", System.getProperty("os.name"));
        report.put("inventory_size", JSON.readTree(INVENTORY.toFile()).path("cases").size());
        report.put("bound_cases", 7); report.put("executed_cases", results.size());
        report.put("passed_checks", results.stream().filter(r -> r.get("status").equals("PASS")).count());
        Map<String, String> hashes = new TreeMap<>();
        for (Path path : List.of(MANIFEST, INVENTORY, Path.of("src/main/resources/prompts/index.json"), Path.of("src/main/resources/application.yml"),
                Path.of("src/main/java/com/tianmu/agent/demo/DemoAdapters.java"), Path.of("src/test/java/com/tianmu/agent/evaluation/EvaluationBaselineTest.java")))
            hashes.put(path.toString().replace('\\', '/'), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        report.put("source_sha256", hashes); report.put("results", results);
        MessageDigest tree = MessageDigest.getInstance("SHA-256");
        List<Path> sources;
        try (var files = Files.walk(Path.of("src/main"))) { sources = new ArrayList<>(files.filter(Files::isRegularFile).toList()); }
        sources.add(Path.of("pom.xml")); sources.sort(Comparator.comparing(p -> p.toString().replace('\\', '/')));
        for (Path path : sources) {
            tree.update(path.toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8)); tree.update((byte) 0);
            tree.update(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        }
        report.put("implementation_tree_sha256", HexFormat.of().formatHex(tree.digest()));
        Path output = Path.of("target/evaluation/engineering-baseline.json"); Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
    }

    @TestConfiguration static class FixtureConfig {
        @Bean @Primary FixtureQuery evaluationQuery() { return new FixtureQuery(); }
    }
    static final class FixtureQuery implements BusinessQueryPort {
        final AtomicInteger calls = new AtomicInteger(); final AtomicReference<Gate> pending = new AtomicReference<>();
        static final class Gate { final CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1); }
        Gate arm() { var gate = new Gate(); assertTrue(pending.compareAndSet(null, gate)); return gate; }
        public MetricData query(MetricQuery request, AccessScope scope) { throw new AssertionError("Controlled query requires CallControl"); }
        @Override public MetricData query(MetricQuery request, AccessScope scope, CallControl control) {
            control.checkpoint();
            if (!scope.videoIds().contains(request.videoId())) throw new AgentException("FORBIDDEN", "Outside fixture scope");
            calls.incrementAndGet(); Gate gate = pending.getAndSet(null);
            if (gate != null) {
                try (var registration = control.cancellation().onCancel(gate.release::countDown)) {
                    gate.started.countDown();
                    try { if (!gate.release.await(5, TimeUnit.SECONDS)) throw new AgentException("TOOL_CALL_TIMEOUT", "Fixture gate timeout"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); control.checkpoint(); throw new AgentException("CANCELLED", "Fixture interrupted"); }
                    control.checkpoint();
                } finally { gate.finished.countDown(); }
            }
            long value = request.date().equals(ANCHOR.plusDays(FIXTURE.path("empty_date_offset_days").asInt()))
                    ? FIXTURE.path("empty_value").asLong() : FIXTURE.path("default_value").asLong();
            return new MetricData(request.videoId(), request.date(), FIXTURE.path("metric").asText(), value,
                    FIXTURE.path("id").asText(), "evaluation-synthetic-v1", Instant.now(), "Asia/Shanghai", true, true);
        }
    }
}
