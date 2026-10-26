package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.core.AgentException;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.DanmakuTypes.ReportRef;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.store.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import java.util.function.Supplier;

/** Artifact persistence shares the run transaction guard, not the runner implementation. */
final class MysqlReportStore implements ReportStore {
    record ActiveRun(UUID session, Object expires) {}
    interface Access {
        RunState load(UUID id, AccessScope owner);
        <T> T read(Supplier<T> action);
        <T> T write(Supplier<T> action);
        ActiveRun lockActive(UUID id, AccessScope owner);
        void ready(UUID id, AccessScope owner);
        byte[] artifact(UUID id, String artifactId, AccessScope owner);
    }
    private final JdbcTemplate db;
    private final ObjectStore objects;
    private final Access access;
    MysqlReportStore(JdbcTemplate db, ObjectStore objects, Access access) { this.db = db; this.objects = objects; this.access = access; }
    @Override public ReportRef saveReport(UUID runId, AccessScope owner, String evidenceId, byte[] html) {
        if (html.length > 262144) throw new AgentException("ARTIFACT_TOO_LARGE", "Report exceeds 256 KiB");
        var evidence = access.load(runId, owner).execution().evidence().get(evidenceId);
        if (evidence == null || evidence.analysis() == null) throw new AgentException("INVALID_EVIDENCE", "Danmaku analysis evidence required");
        String id = "rp_" + UUID.randomUUID(), object = id + ".html", digest = FileObjectStore.hash(html);
        access.write(() -> {
            var run = access.lockActive(runId, owner);
            Integer count = db.queryForObject("SELECT COUNT(*) FROM agent_artifact WHERE tenant_id=? AND run_id=? AND media_type='text/html'", Integer.class, owner.tenantId(), runId.toString());
            if (count != null && count >= 2) throw new AgentException("CAPACITY_EXCEEDED", "Report limit reached");
            db.update("INSERT INTO agent_artifact(tenant_id,owner_user_id,session_id,run_id,artifact_id,object_key,media_type,expires_at) VALUES(?,?,?,?,?,?,'text/html',?)",
                    owner.tenantId(), owner.userId(), run.session().toString(), runId.toString(), id, object, run.expires()); return null;
        });
        try {
            objects.put(object, html);
            access.write(() -> {
                access.lockActive(runId, owner);
                db.update("UPDATE agent_artifact SET status='READY',size_bytes=?,content_sha256=?,ready_at=UTC_TIMESTAMP(6) WHERE tenant_id=? AND artifact_id=?", html.length, digest, owner.tenantId(), id);
                access.ready(runId, owner); return null;
            });
        } catch (RuntimeException e) { try { objects.delete(object); } catch (RuntimeException ignored) {} throw e; }
        return new ReportRef(id, "/api/agent/runs/" + runId + "/reports/" + id);
    }
    @Override public byte[] report(UUID id, AccessScope owner, String artifactId) {
        access.load(id, owner);
        Integer found = access.read(() -> db.queryForObject("SELECT COUNT(*) FROM agent_artifact WHERE tenant_id=? AND owner_user_id=? AND run_id=? AND artifact_id=? AND media_type='text/html'", Integer.class,
                owner.tenantId(), owner.userId(), id.toString(), artifactId));
        if (found == null || found != 1) throw new AgentException("RESULT_NOT_FOUND", "Report not found");
        return access.artifact(id, artifactId, owner);
    }
}
