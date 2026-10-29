package com.tianmu.agent.model.deepseek;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.harness.trace.TraceAttributes;
import com.tianmu.agent.harness.trace.TraceDetails;
import com.tianmu.agent.runtime.control.CallControl;
import com.fasterxml.jackson.databind.*;
import io.opentelemetry.api.trace.Span;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import java.io.*;

/** Single shared credential, independently selected model IDs; no prompt/body/header logging or implicit retries. */
public final class DeepSeekClient {
    private final DeepSeekProperties config;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final com.tianmu.agent.model.ModelContextBuilder contexts;
    private final com.tianmu.agent.model.HarnessContextBuilder harnessContexts;
    private volatile long quotaBlockedUntil;
    private static final long QUOTA_COOLDOWN_NANOS=Duration.ofMinutes(5).toNanos();
    public DeepSeekClient(DeepSeekProperties config, ObjectMapper mapper) {
        this(config, mapper, com.tianmu.agent.model.ContextPolicy.defaults());
    }
    public DeepSeekClient(DeepSeekProperties config, ObjectMapper mapper, com.tianmu.agent.model.ContextPolicy contextPolicy) {
        contexts = new com.tianmu.agent.model.ModelContextBuilder(mapper, contextPolicy);
        harnessContexts = new com.tianmu.agent.model.HarnessContextBuilder(mapper,contextPolicy);
        this.config = config; this.mapper = mapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        http = HttpClient.newBuilder().connectTimeout(config.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public List<Map<String, String>> messages(String instruction, Map<String, ?> input) { return contexts.build(instruction, input); }
    public List<Map<String,Object>> harnessMessages(String instruction,com.tianmu.agent.core.AgentTypes.ModelContext context) { return harnessContexts.build(instruction,context); }
    public JsonNode complete(String model, List<? extends Map<String, ?>> messages, int maxTokens, CallControl control) {
        return complete(model,messages,maxTokens,control,false);
    }
    public JsonNode complete(String model, List<? extends Map<String, ?>> messages, int maxTokens, CallControl control, boolean external) {
        return complete(model,messages,maxTokens,control,external,null);
    }
    public JsonNode completeHarness(String model,List<? extends Map<String,?>> messages,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> tools,int maxTokens,CallControl control) {
        return complete(model,messages,maxTokens,control,false,List.copyOf(tools));
    }
    public JsonNode toolArguments(String text) {
        try {
            JsonNode value=mapper.readTree(text);
            if(value==null || !value.isObject())throw new IllegalArgumentException();
            return value;
        } catch(Exception e) { throw new AgentException("INVALID_DECISION","Invalid native tool arguments"); }
    }
    private JsonNode complete(String model,List<? extends Map<String,?>> messages,int maxTokens,CallControl control,boolean external,List<com.tianmu.agent.core.AgentTypes.ToolDefinition> nativeTools) {
        control.checkpoint();
        if(quotaBlockedUntil!=0 && System.nanoTime()-quotaBlockedUntil<0)
            throw new AgentException("MODEL_QUOTA_EXHAUSTED","Model provider quota is unavailable; calls paused for five minutes");
        contexts.check(messages);
        TraceAttributes.apply(Span.current(),Map.of("model.tool_protocol",nativeTools==null?"json_structured":"native_tools"));
        TraceAttributes.apply(Span.current(), Map.of("model.name", model, "model.provider", "deepseek", "model.max_tokens", maxTokens,
                "model.temperature", 0, "model.streaming", false, "model.first_token_state", "NOT_APPLICABLE", "model.message_count", messages.size(), "model.reasoning_state", "DISABLED"));
        long nanos = Duration.between(Instant.now(), control.deadline()).toNanos();
        if (nanos <= 0) throw unavailable();
        try {
            Map<String,Object> requestBody=new LinkedHashMap<>();
            requestBody.put("model",model);requestBody.put("messages",messages);requestBody.put("thinking",Map.of("type","disabled"));
            requestBody.put("temperature",0);requestBody.put("stream",false);requestBody.put("max_tokens",maxTokens);
            if(nativeTools==null)requestBody.put("response_format",Map.of("type","json_object"));
            if(nativeTools!=null && !nativeTools.isEmpty()) {
                requestBody.put("tools",nativeTools.stream().map(t->Map.of("type","function","function",Map.of("name",t.name(),"description",t.description(),"parameters",t.inputSchema()))).toList());
                requestBody.put("tool_choice","auto");requestBody.put("parallel_tool_calls",false);
            }
            contexts.check(List.of(Map.of("request",requestBody)));
            TraceDetails.capture("input", requestBody, "MODEL_REQUEST");
            byte[] payload = mapper.writeValueAsBytes(requestBody);
            String base = config.baseUrl().toString().replaceAll("/+$", "");
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                    .timeout(Duration.ofNanos(nanos)).header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            long reservation=(long)payload.length+maxTokens+1024;
            JsonNode body = com.tianmu.agent.runtime.control.ModelBudget.execute(control, model, external, reservation, () -> exchange(request, control));
            TraceDetails.capture("provider_output", body, "MODEL_PROVIDER_RESPONSE");
            JsonNode choice = body.path("choices").path(0);
            if (choice.path("finish_reason").isTextual()) TraceAttributes.apply(Span.current(), Map.of("model.finish_reason", choice.path("finish_reason").asText()));
            if(nativeTools!=null && choice.path("message").path("tool_calls").isArray() && !choice.path("message").path("tool_calls").isEmpty()) {
                if(nativeTools.isEmpty() || !"tool_calls".equals(choice.path("finish_reason").asText()))throw new AgentException("INVALID_DECISION","Unexpected native tool call");
                return choice.path("message");
            }
            if (!"stop".equals(choice.path("finish_reason").asText())) throw unavailable();
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual() || content.textValue().isBlank()) throw unavailable();
            if(nativeTools!=null)return choice.path("message");
            JsonNode result = responseObject(content.textValue());
            if (result == null || !result.isObject()) throw unavailable();
            return result;
        } catch (AgentException e) { throw e; }
        catch (Exception e) { control.checkpoint(); throw unavailable(); }
    }
    private JsonNode responseObject(String content) throws com.fasterxml.jackson.core.JsonProcessingException {
        try { return mapper.readTree(content); }
        catch (com.fasterxml.jackson.core.JsonProcessingException original) {
            // Only redundant closing root braces can be removed. All values and duplicate-key checks remain intact.
            String candidate=content.stripTrailing();
            for(int i=0;i<2 && candidate.endsWith("}");i++) {
                candidate=candidate.substring(0,candidate.length()-1).stripTrailing();
                try {
                    JsonNode parsed=mapper.readTree(candidate);
                    if(parsed!=null && parsed.isObject()) {
                        TraceAttributes.apply(Span.current(),Map.of("model.response_repaired",true));
                        return parsed;
                    }
                } catch(com.fasterxml.jackson.core.JsonProcessingException ignored) {}
            }
            TraceAttributes.apply(Span.current(),Map.of("model.validation_error",original.getClass().getSimpleName()));
            throw original;
        }
    }
    private JsonNode exchange(HttpRequest request, CallControl control) {
        try {
            long requestStarted = System.nanoTime();
            CompletableFuture<HttpResponse<byte[]>> future = http.sendAsync(request, info -> new com.tianmu.agent.resource.BoundedHttpBody(131072));
            try (var registration = control.cancellation().onCancel(() -> future.cancel(true))) {
                HttpResponse<byte[]> response;
                try { response = future.get(Math.max(1, Duration.between(Instant.now(), control.deadline()).toNanos()), TimeUnit.NANOSECONDS); }
                catch (TimeoutException e) { future.cancel(true); throw unavailable(); }
                catch (InterruptedException e) { future.cancel(true); Thread.currentThread().interrupt(); throw unavailable(); }
                catch (CancellationException e) { control.checkpoint(); throw unavailable(); }
                control.checkpoint();
                TraceAttributes.apply(Span.current(), Map.of("model.http_status", response.statusCode(), "model.response_ms", Math.max(0, (System.nanoTime() - requestStarted) / 1_000_000)));
                if (response.statusCode() == 401 || response.statusCode() == 403)
                    throw new AgentException("MODEL_AUTH_FAILED", "Model provider authentication failed");
                if(response.statusCode()==402){
                    quotaBlockedUntil=System.nanoTime()+QUOTA_COOLDOWN_NANOS;
                    throw new AgentException("MODEL_QUOTA_EXHAUSTED","Model provider rejected the quota; calls paused for five minutes");
                }
                if (response.statusCode() == 429 || response.statusCode() >= 500) throw unavailable();
                if (response.statusCode() != 200) throw new AgentException("MODEL_REQUEST_REJECTED", "Model provider rejected request");
                return mapper.readTree(response.body());
            }
        } catch (AgentException e) { throw e; }
        catch (Exception e) { control.checkpoint(); throw unavailable(); }
    }
    private IllegalStateException unavailable() { return new IllegalStateException("Model response unavailable"); }
}
