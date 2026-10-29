package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.model.ModelPort;
import com.tianmu.agent.prompt.PromptCatalog;
import com.tianmu.agent.runtime.control.*;
import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.*;
import static com.tianmu.agent.model.deepseek.StructuredModelOutput.*;

final class DeepSeekHarnessModel implements ModelPort {
    private final DeepSeekClient client;
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final String systemPrompt, focusPrompt;
    DeepSeekHarnessModel(DeepSeekClient client, DeepSeekProperties config, ObjectMapper mapper, PromptCatalog prompts) {
        this.client = client; this.config = config; this.mapper = mapper;
        focusPrompt = prompts.text("common.current-focus-boundary"); systemPrompt = prompts.text("harness.system");
    }
    public String modelName() { return config.harnessModel(); }
    public Decision decide(ModelContext context) {
        return decide(context, new CallControl(Instant.now().plusSeconds(30), new CancellationToken()));
    }
    public Decision decide(ModelContext context, CallControl control) {
        String prompt = systemPrompt + "\n\n" + focusPrompt;
        JsonNode result = client.completeHarness(config.harnessModel(), client.harnessMessages(prompt,context),context.availableTools(), config.harnessMaxTokens(), control);
        try {
            if(result.path("tool_calls").isArray()) {
                var calls=result.path("tool_calls");
                if(calls.size()!=1)throw new IllegalArgumentException("Only one native call per step");
                var nativeCall=calls.get(0);var call=nativeCall.path("function");String name=text(call,"name"),id=text(nativeCall,"id");
                if(!"function".equals(text(nativeCall,"type")) || id.length()>160 || !id.matches("[A-Za-z0-9_.:-]+")
                        || context.availableTools().stream().noneMatch(t->t.name().equals(name)))throw new IllegalArgumentException("Unknown native tool");
                var args=client.toolArguments(text(call,"arguments"));
                return new Decision(Kind.TOOL,new ToolCall(name,mapper.convertValue(args,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),id),null,null);
            }
            return switch (text(result, "kind")) {
                case "CLARIFY" -> { fields(result, "kind", "clarification"); yield Decision.clarify(text(result, "clarification")); }
                case "ANSWER" -> {
                    fields(result, "kind", "answer"); JsonNode answer = result.path("answer"); fields(answer, "text", "evidenceIds");
                    if (!answer.path("evidenceIds").isArray()) throw new IllegalArgumentException("Invalid evidence IDs");
                    List<String> ids = new ArrayList<>();
                    for (JsonNode id : answer.path("evidenceIds")) { if (!id.isTextual()) throw new IllegalArgumentException("Invalid evidence ID"); ids.add(id.textValue()); }
                    yield Decision.answer(text(answer, "text"), ids);
                }
                default -> throw new IllegalArgumentException("Unknown decision");
            };
        } catch (RuntimeException e) { throw new AgentException("INVALID_DECISION", "Invalid structured model decision"); }
    }
}
