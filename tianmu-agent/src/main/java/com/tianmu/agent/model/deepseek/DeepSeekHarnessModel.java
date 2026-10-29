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
                if(calls.isEmpty() || calls.size()>4)throw new IllegalArgumentException("Native call batch too large");
                var parsed=new ArrayList<ToolCall>();Set<String> ids=new HashSet<>();String group=calls.size()>1?text(calls.get(0),"id"):null;
                for(var nativeCall:calls) {
                    var call=nativeCall.path("function");String name=text(call,"name"),id=text(nativeCall,"id");
                    if(!"function".equals(text(nativeCall,"type")) || id.length()>160 || !id.matches("[A-Za-z0-9_.:-]+") || !ids.add(id)
                            || context.availableTools().stream().noneMatch(t->t.name().equals(name)))throw new IllegalArgumentException("Unknown native tool");
                    var args=client.toolArguments(text(call,"arguments"));
                    parsed.add(new ToolCall(name,mapper.convertValue(args,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),id,group));
                }
                return new Decision(Kind.TOOL,parsed.get(0),null,null,parsed.subList(1,parsed.size()));
            }
            String content=text(result,"content").strip();
            if(content.contains("<｜｜DSML｜｜"))throw new IllegalArgumentException("Tool syntax in text response");
            if(!content.startsWith("{") && !content.startsWith("[")) {
                // The host attaches only evidence actually admitted in this run; the model need not copy opaque IDs.
                var ids=context.observations().stream().map(ToolResult::evidenceId).filter(Objects::nonNull).distinct().toList();
                return Decision.answer(content,ids);
            }
            result=client.toolArguments(content);
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
