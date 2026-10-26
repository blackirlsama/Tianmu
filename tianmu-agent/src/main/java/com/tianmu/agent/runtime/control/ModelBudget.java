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

    private synchronized void reserve(Runnable beforeDispatch) {
        if (exhausted || limit > 0 && (charged > limit || reservation > limit - charged))
            throw new AgentException("MODEL_TOKEN_BUDGET_EXCEEDED", "Request token budget exhausted");
        if (beforeDispatch != null) beforeDispatch.run();
        charged = add(charged, reservation);
        unknown++;
    }

    private synchronized void settle(JsonNode response) {
        JsonNode in = response.path("usage").path("prompt_tokens");
        JsonNode out = response.path("usage").path("completion_tokens");
        // Missing/partial usage and transport failures retain the entire reservation.
        if (!valid(in) || !valid(out) || in.longValue() > Long.MAX_VALUE - out.longValue()) return;
        long actual = in.longValue() + out.longValue();
        charged = add(charged - reservation, actual);
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
        control.checkpoint();
        ModelBudget budget = control.budget();
        Runnable counter = external ? control.externalCallStarted() : null;
        budget.reserve(counter);
        if (external) {
            com.tianmu.agent.harness.trace.TraceUsage.externalCall();
        }
        JsonNode response = request.get();
        JsonNode usage = response.path("usage");
        com.tianmu.agent.harness.trace.TraceUsage.record(model, token(usage.path("prompt_tokens")), token(usage.path("completion_tokens")),
                token(usage.path("prompt_cache_hit_tokens")), token(usage.path("prompt_cache_miss_tokens")), token(usage.path("completion_tokens_details").path("reasoning_tokens")));
        budget.settle(response);
        return response;
    }
}
