package com.tianmu.agent.runtime.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.tianmu.agent.core.AgentException;


import java.util.Map;
import java.util.function.Supplier;

/** Shared by every provider call in a run, including parallel preparation and nested video tools. */
public final class ModelBudget {
    private final long limit, reservation;
    private long charged, reported, unknown;
    private boolean exhausted;

    public ModelBudget(long limit, long reservation) {
        if (limit < 0 || reservation < 1) throw new IllegalArgumentException("Invalid token budget");
        this.limit = limit; this.reservation = reservation;
    }

    private synchronized long reserve(long estimate, Runnable beforeDispatch) {
        long reserved=Math.max(reservation,estimate);
        if (exhausted || limit > 0 && (charged > limit || reserved > limit - charged))
            throw new AgentException("MODEL_TOKEN_BUDGET_EXCEEDED", "Request token budget exhausted");
        if (beforeDispatch != null) beforeDispatch.run();
        charged = add(charged, reserved);
        unknown++;
        return reserved;
    }

    private synchronized void settle(JsonNode response,long reserved) {
        JsonNode in = response.path("usage").path("prompt_tokens");
        JsonNode out = response.path("usage").path("completion_tokens");
        // Missing/partial usage and transport failures retain the entire reservation.
        if (!valid(in) || !valid(out) || in.longValue() > Long.MAX_VALUE - out.longValue()) return;
        long actual = in.longValue() + out.longValue();
        charged = add(charged - reserved, actual);
        reported++; unknown--;
        if (limit > 0 && charged > limit) {
            exhausted = true;
            throw new AgentException("MODEL_TOKEN_BUDGET_EXCEEDED", "Provider usage exceeded request token budget");
        }
    }

    private static long add(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }

    private static boolean valid(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0;
    }

    private static Long token(JsonNode value) { return valid(value) ? value.longValue() : null; }

    public synchronized Map<String, Object> attributes() {
        return Map.of("budget.token_limit", limit, "budget.charged_tokens", charged,
                "budget.reported_calls", reported, "budget.unsettled_calls", unknown);
    }

    /** Reservation precedes network I/O; accounting precedes provider-content validation. */
    public static JsonNode execute(CallControl control, String model, boolean external, Supplier<JsonNode> request) {
        return execute(control,model,external,0,request);
    }
    /** Text callers supply a conservative serialized-input bound plus the requested maximum output. */
    public static JsonNode execute(CallControl control,String model,boolean external,long estimatedTokens,Supplier<JsonNode> request) {
        if(estimatedTokens<0)throw new IllegalArgumentException("Invalid token estimate");
        control.checkpoint();
        ModelBudget budget = control.budget();
        Runnable counter = external ? control.externalCallStarted() : null;
        long reserved=budget.reserve(estimatedTokens,counter);
        com.tianmu.agent.harness.trace.TraceAttributes.apply(io.opentelemetry.api.trace.Span.current(),Map.of("budget.reserved_tokens",reserved));
        if (external) {
            com.tianmu.agent.harness.trace.TraceUsage.externalCall();
        }
        JsonNode response;
        try { response=request.get(); }
        catch(RuntimeException e){
            com.tianmu.agent.harness.trace.TraceUsage.record(model,null,null,null,null,null);
            throw e;
        }
        JsonNode usage = response.path("usage");
        com.tianmu.agent.harness.trace.TraceUsage.record(model, token(usage.path("prompt_tokens")), token(usage.path("completion_tokens")),
                token(usage.path("prompt_cache_hit_tokens")), token(usage.path("prompt_cache_miss_tokens")), token(usage.path("completion_tokens_details").path("reasoning_tokens")));
        budget.settle(response,reserved);
        return response;
    }
}
