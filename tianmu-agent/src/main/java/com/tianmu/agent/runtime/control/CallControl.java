package com.tianmu.agent.runtime.control;

import java.time.Instant;

/** Do not serialize into prompts. Adapters should apply this deadline and register request cancellation. */
public record CallControl(Instant deadline, CancellationToken cancellation, ModelBudget budget, Runnable externalCallStarted) {
    public CallControl {
        java.util.Objects.requireNonNull(deadline); java.util.Objects.requireNonNull(cancellation);
        java.util.Objects.requireNonNull(budget); java.util.Objects.requireNonNull(externalCallStarted);
    }
    /** Explicit standalone execution, used outside a managed run. */
    public CallControl(Instant deadline, CancellationToken cancellation) {
        this(deadline, cancellation, new ModelBudget(0, 8192), () -> {});
    }
    public CallControl withBudget(ModelBudget value) { return new CallControl(deadline, cancellation, value, externalCallStarted); }
    public void checkpoint() { cancellation.checkpoint(); }
}
