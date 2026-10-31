package com.tianmu.agent.health;

import com.tianmu.agent.runtime.store.ObjectStore;
import com.tianmu.agent.runtime.store.RunRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Required storage affects readiness. Redis and Trace are degradable and do not affect liveness. */
@Component("agentStorageHealthIndicator")
public final class AgentStorageHealthIndicator implements HealthIndicator {
    private final RunRepository repository;
    private final ObjectProvider<ObjectStore> objects;
    private final com.tianmu.agent.runtime.RunService runs;
    public AgentStorageHealthIndicator(RunRepository repository, ObjectProvider<ObjectStore> objects) {
        this(repository, objects, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentStorageHealthIndicator(RunRepository repository, ObjectProvider<ObjectStore> objects,
                                       com.tianmu.agent.runtime.RunService runs) {
        this.repository = repository; this.objects = objects; this.runs = runs;
    }
    @Override public Health health() {
        try {
            repository.verifyReady();
            objects.ifAvailable(ObjectStore::verifyReady);
            if (runs != null && runs.pendingTerminals() > 0) return Health.down().build();
            return Health.up().build();
        } catch (Exception e) {
            // Neither exception text nor credentials/paths are part of an anonymous probe response.
            return Health.down().build();
        }
    }
}
