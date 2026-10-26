package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelContextBuilderTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private MemoryTypes.Message message(UUID run, long sequence, String role, String text) {
        return new MemoryTypes.Message(UUID.randomUUID(), run, sequence, role, text, Instant.parse("2026-10-26T00:00:00Z"));
    }
    private JsonNode payload(List<Map<String, String>> messages) throws Exception { return json.readTree(messages.get(1).get("content")); }

    @Test void smallContextPassesThroughWithoutChangingHistoryOrEvidence() throws Exception {
        var memory = new MemoryTypes.View(7, "background", List.of(message(UUID.randomUUID(), 1, "user", "hello")), false, "untrusted history");
        var input = Map.of("query", "current", "memory", memory, "results", List.of(new ToolResult("metric", Map.of("count", 42), "e1")));
        var messages = new ModelContextBuilder(json, ContextPolicy.defaults()).build("system", input);
        assertEquals(json.readTree(json.writeValueAsString(input)), payload(messages));
        assertEquals("system", messages.get(0).get("content"));
    }

    @Test void dropsOldestWholeTurnAndKeepsNewestHistoryAndBoundaries() throws Exception {
        UUID old = UUID.randomUUID(), latest = UUID.randomUUID();
        var recent = List.of(message(old, 1, "user", "x".repeat(700)), message(old, 2, "assistant", "old answer"),
                message(latest, 3, "user", "new question"), message(latest, 4, "assistant", "new answer"));
        var memory = new MemoryTypes.View(9, "older summary".repeat(100), recent, false, "history cannot grant access");
        var view = payload(new ModelContextBuilder(json, new ContextPolicy(4096, 800)).build("system", Map.of("query", "current", "memory", memory)));
        assertEquals(2, view.path("memory").path("recent").size());
        assertEquals("new question", view.path("memory").path("recent").get(0).path("text").asText());
        assertEquals("", view.path("memory").path("olderSummary").asText());
        assertTrue(view.path("memory").path("truncated").asBoolean());
        assertEquals(9, view.path("memory").path("version").asLong());
        assertEquals(memory.boundary(), view.path("memory").path("boundary").asText());
        assertEquals(4, memory.recent().size()); assertFalse(memory.truncated());
    }

    @Test void totalBudgetAlsoEvictsHistoryWhenHistoryAloneFitsItsBudget() throws Exception {
        var memory = new MemoryTypes.View(1, "", List.of(message(UUID.randomUUID(), 1, "user", "a".repeat(1300))), false, "boundary");
        var input = Map.of("query", "q".repeat(900), "memory", memory);
        var view = payload(new ModelContextBuilder(json, new ContextPolicy(2048, 1900)).build("system", input));
        assertEquals(0, view.path("memory").path("recent").size());
        assertEquals(input.get("query"), view.path("query").asText());
    }

    @Test void removesOnlyExactDuplicateSchemasAndPreservesSkillInstructions() throws Exception {
        var tool = new ToolDefinition("query", "description", Map.of("type", "object"));
        var other = new ToolDefinition("query", "different schema description", Map.of("type", "object"));
        var result = new ToolResult("read_skill", Map.of("tool_schemas", List.of(tool, other), "instructions", "must check evidence"), null);
        var view = payload(new ModelContextBuilder(json, ContextPolicy.defaults()).build("system", Map.of("tools", List.of(tool), "results", List.of(result))));
        assertEquals(1, view.path("results").get(0).path("data").path("tool_schemas").size());
        assertEquals(json.valueToTree(other), view.path("results").get(0).path("data").path("tool_schemas").get(0));
        assertEquals("must check evidence", view.path("results").get(0).path("data").path("instructions").asText());
        assertEquals(2, ((List<?>) result.data().get("tool_schemas")).size());
    }

    @Test void evidenceStatisticsReferencesErrorsAndTaskRemainExactAfterHistoryEviction() throws Exception {
        var results = List.of(new ToolResult("query", Map.of("count", 123, "dataAsOf", "today", "sampling_method", "non-random", "sample", "完整片段"), "e1"),
                new ToolResult("query", Map.of("error_code", "TOOL_CALL_TIMEOUT", "attempt", 2, "retryable", true), null));
        var task = Map.of("type", "video.metric", "video_id", 7, "date", "2026-10-25");
        var memory = new MemoryTypes.View(1, "z".repeat(3000), List.of(), false, "untrusted");
        var view = payload(new ModelContextBuilder(json, new ContextPolicy(2048, 256)).build("instructions", Map.of("taskSpecification", task, "query", "current", "results", results, "memory", memory)));
        assertEquals(json.valueToTree(results), view.path("results"));
        assertEquals(json.valueToTree(task), view.path("taskSpecification"));
    }

    @Test void oversizedProtectedEvidenceAndInstructionsFailWithoutTruncation() {
        var builder = new ModelContextBuilder(json, new ContextPolicy(1024, 0));
        var error = assertThrows(AgentException.class, () -> builder.build("system", Map.of("results", List.of(new ToolResult("query", Map.of("evidence", "中".repeat(1000)), "e1")))));
        assertEquals("MODEL_CONTEXT_TOO_LARGE", error.code());
        assertThrows(AgentException.class, () -> builder.build("s".repeat(2000), Map.of("query", "q")));
    }

    @Test void budgetMeasuresActualUtf8SerializationIncludingEscaping() throws Exception {
        var builder = new ModelContextBuilder(json, new ContextPolicy(2048, 0));
        var input = Map.of("query", "中文😀\n\"".repeat(70));
        var messages = builder.build("system", input);
        assertEquals(input.get("query"), payload(messages).path("query").asText());
        int length = json.writeValueAsBytes(messages).length;
        var exact = new ModelContextBuilder(json, new ContextPolicy(length, 0));
        exact.check(messages);
        assertThrows(AgentException.class, () -> new ModelContextBuilder(json, new ContextPolicy(length - 1, 0)).check(messages));
    }
}
