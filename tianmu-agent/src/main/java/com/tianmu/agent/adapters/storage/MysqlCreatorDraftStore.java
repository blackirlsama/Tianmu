package com.tianmu.agent.adapters.storage;

import com.tianmu.agent.interaction.*;
import com.tianmu.agent.core.AgentException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import javax.sql.DataSource;
import java.util.UUID;

@Component
@DependsOn("agentMigrations")
@ConditionalOnProperty(name="agent.storage.mode", havingValue="mysql")
public final class MysqlCreatorDraftStore implements CreatorDraftStore {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public MysqlCreatorDraftStore(DataSource source, ObjectMapper json) { db = new JdbcTemplate(source); this.json = json; }
    private String encode(CreatorDraft draft) {
        try { return json.writeValueAsString(draft); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public void create(CreatorDraft draft) {
        try { db.update("INSERT INTO agent_creator_draft(run_id,payload) VALUES(?,?)", draft.runId().toString(), encode(draft)); }
        catch (org.springframework.dao.DuplicateKeyException e) { throw new AgentException("DRAFT_CONFLICT", "该任务已有草稿。"); }
    }
    public CreatorDraft get(UUID id) {
        var values = db.queryForList("SELECT payload FROM agent_creator_draft WHERE run_id=?", String.class, id.toString());
        try { return values.isEmpty() ? null : json.readValue(values.get(0), CreatorDraft.class); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored creator draft", e); }
    }
    public boolean replace(CreatorDraft expected, CreatorDraft next) {
        return db.update("UPDATE agent_creator_draft SET payload=? WHERE run_id=? AND BINARY payload=BINARY ?",
                encode(next), expected.runId().toString(), encode(expected)) == 1;
    }
}
