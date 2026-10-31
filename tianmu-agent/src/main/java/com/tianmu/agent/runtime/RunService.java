package com.tianmu.agent.runtime;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.tianmu.agent.harness.RuntimeHarness;
import com.tianmu.agent.core.StopReason;

@Service
public class RunService {
    private final Map<UUID, RunState> runs = new ConcurrentHashMap<>();
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor;
    private final boolean ownsExecutor;
    private final RequestOrchestrator orchestrator;
    private final RuntimeHarness harness;
    private final com.tianmu.agent.runtime.store.RunRepository repository;
    private final com.tianmu.agent.runtime.store.ConversationStore conversations;
    private List<RunCompletionListener> completionListeners=List.of();
    @org.springframework.beans.factory.annotation.Autowired
    void completionListeners(List<RunCompletionListener> listeners){completionListeners=List.copyOf(listeners);}
    @org.springframework.beans.factory.annotation.Autowired
    void recoveryMetrics(io.micrometer.core.instrument.MeterRegistry metrics) {
        io.micrometer.core.instrument.Gauge.builder("agent.run.terminal.pending", this, RunService::pendingTerminals).register(metrics);
    }
    public RunService(RequestOrchestrator orchestrator, RuntimeHarness harness, com.tianmu.agent.runtime.store.RunRepository repository) {
        this(orchestrator, harness, repository, com.tianmu.agent.config.ExecutionConfiguration.standalone("agent-runs", 2, 4, 32), true);
    }
    public RunService(RequestOrchestrator orchestrator, RuntimeHarness harness, com.tianmu.agent.runtime.store.RunRepository repository,
            @org.springframework.beans.factory.annotation.Qualifier("runExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor) {
        this(orchestrator, harness, repository, executor, false);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RunService(RequestOrchestrator orchestrator, RuntimeHarness harness, com.tianmu.agent.runtime.store.RunRepository repository,
            @org.springframework.beans.factory.annotation.Qualifier("runExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor,
            com.tianmu.agent.runtime.store.ConversationStore conversations) {
        this(orchestrator, harness, repository, executor, false, conversations);
    }
    private RunService(RequestOrchestrator orchestrator, RuntimeHarness harness, com.tianmu.agent.runtime.store.RunRepository repository,
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor, boolean owns) {
        this(orchestrator, harness, repository, executor, owns, repository instanceof com.tianmu.agent.runtime.store.ConversationStore value
                ? value : new com.tianmu.agent.runtime.store.ConversationStore() {});
    }
    private RunService(RequestOrchestrator orchestrator, RuntimeHarness harness, com.tianmu.agent.runtime.store.RunRepository repository,
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor, boolean owns, com.tianmu.agent.runtime.store.ConversationStore conversations) {
        this.orchestrator = orchestrator; this.harness = harness; this.repository = repository; this.executor = executor; this.ownsExecutor = owns;
        this.conversations = conversations;
    }
    public RunState create(String message, AccessScope owner) { return create(message, owner, null, null, null); }
    public RunState create(String message, AccessScope owner, UUID session, String key, UUID reply) { return create(message, owner, session, key, reply, null); }
    public synchronized RunState create(String message, AccessScope owner, UUID session, String key, UUID reply, com.tianmu.agent.core.RequestContext context) {
        if (key != null && !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw new AgentException("INVALID_REQUEST", "Invalid Idempotency-Key");
        // Demo retention is lazy and process-local; production must use a persistent RunRepository.
        runs.values().removeIf(run -> {
            boolean expired = com.tianmu.agent.core.AgentTypes.terminal(run.snapshot().status())
                    && (repository.durable() || run.createdAt.isBefore(Instant.now().minusSeconds(3600)));
            if (expired) harness.forget(run.id);
            return expired;
        });
        if (runs.values().stream().filter(r -> !com.tianmu.agent.core.AgentTypes.terminal(r.snapshot().status())).count() >= 200) throw new AgentException("CAPACITY_EXCEEDED", "Demo run capacity reached");
        var created = repository.create(message, owner, session, key, reply, context);
        if (!created.fresh()) return created.run();
        RunState run = created.run();
        runs.put(run.id, run);
        try {
            submit(run);
        }
        catch (AgentException e) { run.fail(e.code()); }
        catch (RejectedExecutionException e) { run.fail("QUEUE_FULL"); }
        return run;
    }
    private void submit(RunState run) {
        harness.start(run);
        run.onTerminal(snapshot->completionListeners.forEach(listener->{
            try{listener.completed(run,snapshot);}
            catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(RunService.class).warn("completion_listener_failed run_id={} type={}",run.id,e.getClass().getSimpleName());}
        }));
        Runnable work = () -> orchestrator.execute(run);
        run.onTerminal(snapshot -> executor.getThreadPoolExecutor().remove(work));
        executor.execute(work);
        if (com.tianmu.agent.core.AgentTypes.terminal(run.snapshot().status())) executor.getThreadPoolExecutor().remove(work);
    }
    public RunState owned(UUID id, AccessScope caller) {
        RunState run = repository.load(id, caller);
        if (run == null || !run.owner.tenantId().equals(caller.tenantId())
                || !run.owner.userId().equals(caller.userId()))
            throw new AgentException("RUN_NOT_FOUND", "Run not found");
        if (!caller.videoIds().containsAll(run.owner.videoIds()))
            throw new AgentException("FORBIDDEN", "Access scope changed; old results cannot be read");
        // HTTP cancellation must stop the actual worker, not a newly materialized storage projection.
        RunState local = runs.get(id);
        if (local != null) {
            local.adoptTerminal(run.snapshot());
            run = local;
        }
        io.opentelemetry.api.trace.Span.current().setAttribute("run.id", id.toString());
        return run;
    }
    public Snapshot cancel(UUID id, AccessScope caller) {
        RunState run = owned(id, caller);
        if (!com.tianmu.agent.core.AgentTypes.terminal(run.snapshot().status())) {
            // Also retain a queued run cancelled before the startup recovery scan claims it.
            RunState existing = runs.putIfAbsent(id, run);
            if (existing != null) run = existing;
        }
        run.cancel();
        if (run.terminalPending()) throw new AgentException("STORAGE_UNAVAILABLE", "Execution stopped; durable termination is pending");
        return run.snapshot();
    }
    public long pendingTerminals() { return runs.values().stream().filter(RunState::terminalPending).count(); }
    public java.util.List<com.tianmu.agent.core.MemoryTypes.Message> messages(UUID session, AccessScope owner, long before) { return conversations.messages(session, owner, before); }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
    public void maintain() {
        Instant now = Instant.now();
        // Retry only durable outcomes, oldest due first. Bound work per tick and isolate each failure.
        runs.values().stream().map(run -> Map.entry(run, run.terminalReconciliationAt()))
                .filter(entry -> !now.isBefore(entry.getValue())).sorted(Map.Entry.comparingByValue()).limit(8).forEach(entry -> {
                    RunState run = entry.getKey();
                    try { run.prepareTerminalReconciliation(now); repository.refresh(run); run.reconcileTerminal(now); }
                    catch (AgentException e) {
                        if ("RUN_NOT_FOUND".equals(e.code())) {
                            run.discard(); runs.remove(run.id, run); harness.discard(run.id);
                            completionListeners.forEach(listener -> {
                                try { listener.discarded(run); }
                                catch (RuntimeException error) { org.slf4j.LoggerFactory.getLogger(RunService.class).warn("discard_listener_failed run_id={} type={}", run.id, error.getClass().getSimpleName()); }
                            });
                        } else {
                            run.deferTerminalReconciliation(now);
                            org.slf4j.LoggerFactory.getLogger(RunService.class).warn("terminal_reconciliation_failed run_id={} code={}", run.id, e.code());
                        }
                    }
                    catch (Exception e) {
                        run.deferTerminalReconciliation(now);
                        org.slf4j.LoggerFactory.getLogger(RunService.class).warn("terminal_reconciliation_failed run_id={} type={}", run.id, e.getClass().getSimpleName());
                    }
                });
        try {
            for (RunState run : repository.recoverQueued()) if (runs.putIfAbsent(run.id, run) == null) {
                try { submit(run); }
                catch (AgentException e) { run.fail(e.code()); }
                catch (RejectedExecutionException e) { run.fail("QUEUE_FULL"); }
            }
        } catch (Exception e) { org.slf4j.LoggerFactory.getLogger(RunService.class).warn("storage_maintenance_failed type={}", e.getClass().getSimpleName()); }
    }
    @PreDestroy public void shutdown() {
        runs.values().forEach(run -> { try { run.cancel(StopReason.SHUTDOWN); } catch (Exception e) { /* Durable state is reconciled on restart. */ } });
        if (ownsExecutor) executor.shutdown();
        try { executor.getThreadPoolExecutor().awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
