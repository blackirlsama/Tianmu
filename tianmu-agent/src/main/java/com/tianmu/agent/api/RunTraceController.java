package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.Snapshot;
import com.tianmu.agent.harness.trace.*;
import com.tianmu.agent.runtime.store.RunRepository;
import org.springframework.core.env.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/agent/runs/{runId}/trace")
public final class RunTraceController {
    public record Response(Snapshot run, List<TraceEnvelope> spans, String source, boolean truncated,
                           String modelMode, String businessMode) {}
    private final IdentityPort identity;
    private final RunRepository runs;
    private final RunTraceQuery traces;
    private final Environment environment;
    public RunTraceController(IdentityPort identity, RunRepository runs, RunTraceQuery traces, Environment environment) {
        this.identity = identity; this.runs = runs; this.traces = traces; this.environment = environment;
    }
    @GetMapping
    public ResponseEntity<Response> get(@PathVariable UUID runId,
            @RequestHeader(value="Authorization", required=false) String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new AgentException("UNAUTHORIZED", "Bearer authentication required");
        var run = runs.load(runId, identity.forRun(auth.substring(7), runId));
        var snapshot = run.snapshot();
        var result = traces.read(runId, snapshot.traceId());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(new Response(snapshot, result.spans(), result.source(), result.truncated(),
                        environment.acceptsProfiles(Profiles.of("deepseek")) ? "PROVIDER" : "DEMO",
                        environment.acceptsProfiles(Profiles.of("business")) ? "BUSINESS" : "DEMO"));
    }
}
