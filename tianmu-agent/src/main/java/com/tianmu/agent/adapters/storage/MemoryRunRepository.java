package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.store.*;
import com.tianmu.agent.runtime.events.InMemoryRunEventStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Explicitly ephemeral development mode; durable mode never falls back to this adapter. */
@Component
@ConditionalOnProperty(name="agent.storage.mode", havingValue="memory", matchIfMissing=true)
public final class MemoryRunRepository implements RunRepository, ReportStore, ConversationStore {
    private final Map<UUID, RunState> runs = new HashMap<>();
    private final Map<String, UUID> keys = new HashMap<>();
    private final Map<UUID, RequestContext> contexts = new HashMap<>();
    private record FocusAt(long turn, MemoryTypes.Focus value) {}
    private final Map<UUID,FocusAt> focuses = new HashMap<>();
    private record Report(UUID runId, byte[] html) {}
    private final Map<String, Report> reports = new HashMap<>();
    public synchronized Created create(String message, AccessScope owner, UUID session, String key, UUID reply, RequestContext context) {
        String query = com.tianmu.agent.application.RequestMessages.execution(message, context);
        runs.values().removeIf(r -> r.createdAt.isBefore(Instant.now().minusSeconds(3600)) && AgentTypes.terminal(r.snapshot().status()));
        keys.values().removeIf(id -> !runs.containsKey(id));
        contexts.keySet().removeIf(id -> !runs.containsKey(id));
        focuses.keySet().removeIf(sid -> runs.values().stream().noneMatch(r->r.sessionId.equals(sid)));
        reports.values().removeIf(report -> !runs.containsKey(report.runId()));
        if (runs.size() >= 200) throw new AgentException("CAPACITY_EXCEEDED", "Ephemeral run capacity reached");
        if (reply != null) throw new AgentException("STORAGE_REQUIRED", "Clarification resumption requires persistent storage");
        String identity = owner.tenantId() + ":" + owner.userId() + ":" + key;
        if (key != null && keys.containsKey(identity)) {
            RunState existing = load(keys.get(identity), owner);
            if (!existing.message.equals(message) || !Objects.equals(contexts.get(existing.id), context) || !existing.query().equals(query) || session != null && !session.equals(existing.sessionId))
                throw new AgentException("IDEMPOTENCY_CONFLICT", "Idempotency key has a different request");
            return new Created(existing, false);
        }
        if (session != null) {
            UUID requested = session;
            if (runs.values().stream().noneMatch(r -> r.sessionId.equals(requested) && r.owner.tenantId().equals(owner.tenantId()) && r.owner.userId().equals(owner.userId())))
                throw new AgentException("SESSION_NOT_FOUND", "Session not found");
        } else session = UUID.randomUUID();
        UUID finalSession = session;
        runs.values().stream().filter(r -> r.sessionId.equals(finalSession) && !AgentTypes.terminal(r.snapshot().status()))
                .forEach(r -> r.cancel(StopReason.SUPERSEDED));
        long turn = runs.values().stream().filter(r -> r.sessionId.equals(finalSession)).count() + 1;
        Instant now = Instant.now();
        RunState run = new RunState(UUID.randomUUID(), session, turn, owner, message, query, now, now.plusSeconds(30),
                RunBackend.MEMORY, new InMemoryRunEventStore(), new InMemoryEvidenceStore(), false);
        run.pageContext(context);
        run.emit("PROGRESS", Map.of("stage", "queued", "run_id", run.id.toString()));
        runs.put(run.id, run); if (context != null) contexts.put(run.id, context); if (key != null) keys.put(identity, run.id);
        return new Created(run, true);
    }
    @Override public synchronized Set<Long> resourcesForRun(UUID id, AccessScope caller) {
        RunState run = runs.get(id);
        if (run == null || !run.owner.tenantId().equals(caller.tenantId()) || !run.owner.userId().equals(caller.userId()))
            throw new AgentException("RUN_NOT_FOUND", "Run not found");
        return run.owner.videoIds();
    }
    public synchronized RunState load(UUID id, AccessScope caller) {
        RunState run = runs.get(id);
        if (run == null || !run.owner.tenantId().equals(caller.tenantId()) || !run.owner.userId().equals(caller.userId()))
            throw new AgentException("RUN_NOT_FOUND", "Run not found");
        if (!caller.videoIds().containsAll(run.owner.videoIds())) throw new AgentException("FORBIDDEN", "Historical scope is no longer accessible");
        return run;
    }
    private List<MemoryTypes.Message> history(UUID session, AccessScope owner, long beforeTurn) {
        List<MemoryTypes.Message> result = new ArrayList<>();
        runs.values().stream().filter(r -> r.sessionId.equals(session) && r.turnSeq < beforeTurn
                && r.owner.tenantId().equals(owner.tenantId()) && r.owner.userId().equals(owner.userId()))
                .sorted(Comparator.comparingLong(r -> r.turnSeq)).forEach(r -> {
                    var snapshot = r.snapshot();
                    result.add(new MemoryTypes.Message(UUID.nameUUIDFromBytes((r.id+":user").getBytes(java.nio.charset.StandardCharsets.UTF_8)),r.id,r.turnSeq*2-1,"user",r.message,r.createdAt));
                    String answer = snapshot.answer() != null ? snapshot.answer().text() : snapshot.clarification();
                    if (answer != null) result.add(new MemoryTypes.Message(UUID.nameUUIDFromBytes((r.id+":assistant").getBytes(java.nio.charset.StandardCharsets.UTF_8)),r.id,r.turnSeq*2,"assistant",
                            owner.videoIds().containsAll(r.owner.videoIds()) ? answer : "[先前视频结果当前无权限，已隐藏]",r.createdAt));
                });
        return result;
    }
    @Override public synchronized MemoryTypes.View memory(RunState run) {
        var focus = focuses.get(run.sessionId);
        return new com.tianmu.agent.application.MemoryBuilder().build(run.turnSeq,history(run.sessionId,run.owner,run.turnSeq))
                .withContext(run.pageContext(),focus != null && focus.turn()<run.turnSeq ? focus.value() : null);
    }
    @Override public synchronized void focus(RunState run, MemoryTypes.Focus value) {
        var old = focuses.get(run.sessionId);
        if (old == null || old.turn() <= run.turnSeq) focuses.put(run.sessionId,new FocusAt(run.turnSeq,value));
    }
    @Override public synchronized List<MemoryTypes.Message> messages(UUID session, AccessScope owner, long before) {
        if (before < 1) throw new AgentException("INVALID_CURSOR","Invalid history cursor");
        if (runs.values().stream().noneMatch(r -> r.sessionId.equals(session) && r.owner.tenantId().equals(owner.tenantId()) && r.owner.userId().equals(owner.userId())))
            throw new AgentException("SESSION_NOT_FOUND","Session not found");
        var all = history(session,owner,Long.MAX_VALUE).stream().filter(m -> m.sequence()<before).toList();
        return all.subList(Math.max(0,all.size()-50),all.size());
    }
    @Override public synchronized com.tianmu.agent.core.DanmakuTypes.ReportRef saveReport(UUID runId, AccessScope owner, String evidenceId, byte[] html) {
        RunState run = load(runId, owner); run.checkpoint();
        Evidence evidence = run.execution().evidence().get(evidenceId);
        if (evidence == null || evidence.analysis() == null || !evidence.owner().equals(owner) || !evidence.runId().equals(runId) || html.length > 262144)
            throw new AgentException("INVALID_EVIDENCE", "Invalid report");
        if (AgentTypes.terminal(run.snapshot().status())) throw new AgentException("RUN_TERMINATED", "Task is terminal");
        if (reports.values().stream().filter(r -> r.runId().equals(runId)).count() >= 2) throw new AgentException("CAPACITY_EXCEEDED", "Report limit reached");
        String id = "rp_" + UUID.randomUUID(); reports.put(id, new Report(runId, html.clone()));
        return new com.tianmu.agent.core.DanmakuTypes.ReportRef(id, "/api/agent/runs/" + runId + "/reports/" + id);
    }
    @Override public synchronized byte[] report(UUID runId, AccessScope caller, String artifactId) {
        load(runId, caller); Report report = reports.get(artifactId);
        if (report == null || !report.runId().equals(runId)) throw new AgentException("RESULT_NOT_FOUND", "Report not found");
        return report.html().clone();
    }
}
