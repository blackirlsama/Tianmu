package com.tianmu.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceAttributes;
import io.opentelemetry.api.trace.Span;
import java.util.*;

/** Builds a disposable model view. Never mutates stored history, observations or evidence. */
public final class ModelContextBuilder {
    private final ObjectMapper json;
    private final ContextPolicy policy;

    public ModelContextBuilder(ObjectMapper json, ContextPolicy policy) {
        this.json = json; this.policy = policy;
    }

    public List<Map<String, String>> build(String instruction, Map<String, ?> input) {
        ObjectNode view = json.valueToTree(input);
        int before = bytes(messages(instruction, view));
        int removed = 0, schemas = deduplicateSchemas(view), released = releaseClassifiedBatches(view);
        JsonNode memory = view.path("memory");
        while (bytes(memory) > policy.maxHistoryBytes() && trimHistory(memory)) removed++;
        List<Map<String, String>> result = messages(instruction, view);
        while (bytes(result) > policy.maxInputBytes() && trimHistory(memory)) {
            removed++;
            result = messages(instruction, view);
        }
        TraceAttributes.apply(Span.current(), Map.of("context.input_bytes", before,
                "context.output_bytes", bytes(result), "context.changed", removed > 0 || schemas > 0 || released>0,
                "context.history_removed", removed, "context.schemas_removed", schemas,
                "context.classified_batches_released",released,
                "context.byte_limit", policy.maxInputBytes()));
        check(result);
        return result;
    }
    private int releaseClassifiedBatches(ObjectNode view) {
        if(!(view.path("results") instanceof ArrayNode results))return 0;
        Map<String,ObjectNode> latest=new HashMap<>();
        for(JsonNode result:results)if(result.path("data") instanceof ObjectNode data && data.path("classification").isObject()) {
            String evidence=data.path("evidence_id").asText("");
            if(!evidence.isEmpty())latest.put(evidence,data);
        }
        int count=0;
        for(JsonNode result:results)if(result.path("data") instanceof ObjectNode data && data.path("classification") instanceof ObjectNode classification) {
            ObjectNode last=latest.get(data.path("evidence_id").asText(""));
            // Keep unread/current groups. Only a later admitted classification can supersede an earlier batch.
            if(last!=null && last!=data && last.path("classification").path("offset").asInt(-1)>classification.path("offset").asInt(-1)) {
                classification.remove("groups");classification.remove("topics");classification.put("superseded",true);count++;
            }
        }
        return count;
    }

    /** Final guard also applies to callers that supply their own messages. */
    public void check(List<Map<String, String>> messages) {
        if (bytes(messages) > policy.maxInputBytes())
            throw new AgentException("MODEL_CONTEXT_TOO_LARGE", "Required instructions, task and evidence exceed model context budget");
    }

    private boolean trimHistory(JsonNode memory) {
        if (!(memory instanceof ObjectNode object)) return false;
        if (!object.path("olderSummary").asText("").isEmpty()) {
            object.put("olderSummary", ""); object.put("truncated", true); return true;
        }
        if (!(object.path("recent") instanceof ArrayNode recent) || recent.isEmpty()) return false;
        // Remove a whole oldest turn so a retained answer cannot lose its paired user message.
        JsonNode runId = recent.get(0).get("runId");
        do { recent.remove(0); }
        while (!recent.isEmpty() && runId != null && !runId.isNull() && runId.equals(recent.get(0).get("runId")));
        object.put("truncated", true);
        return true;
    }

    private int deduplicateSchemas(ObjectNode view) {
        if (!(view.path("tools") instanceof ArrayNode tools) || !(view.path("results") instanceof ArrayNode results)) return 0;
        Set<JsonNode> exposed = new HashSet<>(); tools.forEach(exposed::add);
        int removed = 0;
        for (JsonNode result : results) {
            if (!"read_skill".equals(result.path("tool").asText())) continue;
            JsonNode schemas = result.path("data").path("tool_schemas");
            if (!(schemas instanceof ArrayNode array)) continue;
            for (int i = array.size() - 1; i >= 0; i--) {
                if (exposed.contains(array.get(i))) { array.remove(i); removed++; }
            }
        }
        return removed;
    }

    private List<Map<String, String>> messages(String instruction, ObjectNode input) {
        try {
            return List.of(Map.of("role", "system", "content", instruction),
                    Map.of("role", "user", "content", json.writeValueAsString(input)));
        } catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot serialize model context", e); }
    }

    private int bytes(Object value) {
        try { return json.writeValueAsBytes(value).length; }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Cannot measure model context", e); }
    }
}
