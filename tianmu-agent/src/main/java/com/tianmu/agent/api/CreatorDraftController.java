package com.tianmu.agent.api;

import com.tianmu.agent.access.IdentityPort;
import com.tianmu.agent.interaction.*;
import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.runtime.RunService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;

@RestController
@RequestMapping("/api/agent/runs/{runId}/creator-draft")
public final class CreatorDraftController {
    public record Confirmation(List<CreatorDraft.Item> items) {}
    private final IdentityPort identity;
    private final RunService runs;
    private final CreatorPublicationService service;
    public CreatorDraftController(IdentityPort identity, RunService runs, CreatorPublicationService service) {
        this.identity = identity; this.runs = runs; this.service = service;
    }
    private com.tianmu.agent.core.AgentTypes.AccessScope access(UUID id, String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw new AgentException("UNAUTHORIZED", "请先登录。");
        var scope = identity.forRun(auth.substring(7), id);
        var run = runs.owned(id, scope);
        if (run.snapshot().status() != com.tianmu.agent.core.AgentTypes.Status.SUCCEEDED)
            throw new AgentException("DRAFT_NOT_READY", "请等待草稿生成完成。");
        return scope;
    }
    @GetMapping
    public ResponseEntity<CreatorDraft> get(@PathVariable UUID runId, @RequestHeader(value="Authorization", required=false) String auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.owned(runId, access(runId, auth)));
    }
    @PostMapping("/confirm")
    public ResponseEntity<CreatorDraft> confirm(@PathVariable UUID runId, @RequestHeader(value="Authorization", required=false) String auth,
                                               @RequestBody Confirmation body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.confirm(runId, body.items(), access(runId, auth)));
    }
}
