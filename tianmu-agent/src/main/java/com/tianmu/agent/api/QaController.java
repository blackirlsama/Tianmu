package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.faq.*;
import com.tianmu.agent.runtime.RunService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/agent/qa")
public final class QaController {
    public record Change(@Min(0) long version) {}
    public record ImportRun(@NotNull UUID runId) {}
    public record Catalog(QaPolicy policy,List<QaState.View> items) {}
    private final IdentityPort identity;private final QaCatalog catalog;private final RunService runs;
    public QaController(IdentityPort identity,QaCatalog catalog,RunService runs){this.identity=identity;this.catalog=catalog;this.runs=runs;}
    private String credential(String auth){if(auth==null || !auth.startsWith("Bearer "))throw new AgentException("UNAUTHORIZED","Bearer authentication is required");return auth.substring(7);}
    @GetMapping
    public ResponseEntity<Catalog> list(@RequestHeader(value="Authorization",required=false) String auth){
        var owner=identity.authenticate(credential(auth));return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Catalog(catalog.policy(),catalog.list(owner)));
    }
    @PostMapping("/{id}/disable")
    public QaState.View disable(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody @Valid Change input){
        return catalog.change(identity.authenticate(credential(auth)),id,input.version(),false);
    }
    @PostMapping("/{id}/reset")
    public QaState.View reset(@PathVariable String id,@RequestHeader(value="Authorization",required=false) String auth,@RequestBody @Valid Change input){
        return catalog.change(identity.authenticate(credential(auth)),id,input.version(),true);
    }
    /** Recovers missed best-effort notifications; importing the same session never adds another vote. */
    @PostMapping("/from-run")
    public Catalog fromRun(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody @Valid ImportRun input){
        String token=credential(auth);var owner=identity.forRun(token,input.runId());var run=runs.owned(input.runId(),owner);
        catalog.completed(run,run.snapshot());return new Catalog(catalog.policy(),catalog.list(owner));
    }
}
