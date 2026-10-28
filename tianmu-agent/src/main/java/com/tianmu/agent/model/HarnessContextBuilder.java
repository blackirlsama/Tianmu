package com.tianmu.agent.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.harness.trace.TraceAttributes;
import io.opentelemetry.api.trace.Span;
import java.util.*;

/** Native conversation roles; only the current decision's environment, capabilities and evidence are loaded. */
public final class HarnessContextBuilder {
    private final ObjectMapper json;
    private final ContextPolicy policy;
    private final ModelContextBuilder bounds;
    public HarnessContextBuilder(ObjectMapper json,ContextPolicy policy) {
        this.json=json.copy().findAndRegisterModules();this.policy=policy;this.bounds=new ModelContextBuilder(this.json,policy);
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid context",e); }
    }
    private int bytes(Object value) { return encode(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length; }
    private String section(String name,Object value) {
        // Data cannot close a host-defined section or impersonate another message role.
        String body=encode(value).replace("&","\\u0026").replace("<","\\u003c").replace(">","\\u003e");
        return "<"+name+">"+body+"</"+name+">";
    }
    private Map<String,Object> message(String role,String content) { return Map.of("role",role,"content",content); }
    public List<Map<String,Object>> build(String instruction,ModelContext context) {
        var memory=context.memory();
        List<MemoryTypes.Message> history=new ArrayList<>(memory.recent());
        if(ConversationTopics.switchesTopic(context.message()))history.clear();
        // A small recent window is the default; older messages remain available through read_history.
        while(!history.isEmpty() && (history.size()>4 || bytes(history)>Math.min(8192,policy.maxHistoryBytes()))) removeOldestTurn(history);
        ObjectNode working=json.createObjectNode();working.set("tools",json.valueToTree(context.availableTools()));
        working.set("results",json.valueToTree(context.observations()));
        int schemas=bounds.deduplicateSchemas(working),released=bounds.releaseClassifiedBatches(working);
        List<Map<String,Object>> messages=assemble(instruction,context,history,working);
        while(bytes(messages)>policy.maxInputBytes() && !history.isEmpty()) {
            removeOldestTurn(history);messages=assemble(instruction,context,history,working);
        }
        bounds.check(messages);
        TraceAttributes.apply(Span.current(),Map.of("context.format","native_roles_v1","context.output_bytes",bytes(messages),
                "context.history_messages",history.size(),"context.schemas_removed",schemas,"context.classified_batches_released",released,
                "context.byte_limit",policy.maxInputBytes()));
        return List.copyOf(messages);
    }
    private void removeOldestTurn(List<MemoryTypes.Message> history) {
        if(history.isEmpty())return;
        UUID first=history.get(0).runId();
        do { history.remove(0); } while(!history.isEmpty() && Objects.equals(first,history.get(0).runId()));
    }
    private List<Map<String,Object>> assemble(String instruction,ModelContext context,List<MemoryTypes.Message> history,ObjectNode working) {
        List<Map<String,Object>> out=new ArrayList<>();out.add(message("system",instruction));
        for(var prior:history) if(Set.of("user","assistant").contains(prior.role())) out.add(message(prior.role(),prior.text()));
        StringBuilder control=new StringBuilder();
        if(context.memory().environment()!=null) {
            var page=context.memory().environment();Map<String,Object> environment=new LinkedHashMap<>();environment.put("videoId",page.videoId());
            if(!page.videoTitle().isBlank())environment.put("videoTitle",page.videoTitle());
            if(page.playbackMs()!=null)environment.put("playbackMs",page.playbackMs());
            control.append(section("environment",environment)).append('\n');
        }
        var focus=context.memory().focus();
        String current=context.task()==null ? context.message():context.task().originalQuery();
        if(focus!=null && !focus.request().equals(current))control.append(section("current_focus",focus)).append('\n');
        if(context.task()!=null && !Set.of("conversation","unclassified").contains(context.task().specification().type()))
            control.append(section("confirmed_conditions",context.task().specification())).append('\n');
        Map<String,Object> runtime=new LinkedHashMap<>();runtime.put("businessDate",context.businessDate().toString());runtime.put("remainingSteps",context.remainingSteps());
        if(!context.activeSkills().isEmpty())runtime.put("activeSkills",context.activeSkills());
        if(context.memory().truncated() || history.size()<context.memory().recent().size())runtime.put("olderHistoryAvailable",true);
        if(!history.isEmpty())runtime.put("historyBefore",history.get(0).sequence());
        control.append(section("runtime",runtime)).append('\n');
        if(!context.availableTools().isEmpty())control.append(section("available_tools",context.availableTools()));
        out.add(message("system",control.toString()));out.add(message("user",current));
        ArrayNode results=(ArrayNode)working.get("results");
        for(int i=0;i<results.size();i++) {
            ToolResult observed=context.observations().get(i);
            ToolCall call=context.exchanges().stream().filter(e->e.result().equals(observed)).map(ToolExchange::call).findFirst()
                    .orElse(new ToolCall(observed.tool(),Map.of()));
            String id="call_host_"+i;
            // Keep the JSON decision alongside native metadata; this is still a JSON decision protocol.
            String decision=encode(Map.of("kind","TOOL","call",call));
            out.add(Map.of("role","assistant","content",decision,"tool_calls",List.of(Map.of("id",id,"type","function",
                    "function",Map.of("name",call.name(),"arguments",encode(call.arguments()))))));
            ObjectNode result=((ObjectNode)results.get(i)).deepCopy();result.remove("terminalAnswer");
            out.add(Map.of("role","tool","tool_call_id",id,"content",encode(result)));
        }
        if(!results.isEmpty() && !context.finalizing())out.add(message("system","工具结果已返回。继续处理本轮用户目标，只输出一个符合既定 kind/call/answer 格式的 JSON 对象；不要输出空白。"));
        if(context.finalizing())out.add(message("system",context.systemDirective()));
        return out;
    }
}
