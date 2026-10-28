package com.tianmu.agent.adapters.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.application.MemoryBuilder;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.RunState;
import com.tianmu.agent.runtime.store.ConversationStore;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.nio.charset.StandardCharsets;

/** Read-side conversation queries and scope-versioned cache; run/session authority stays with the writer. */
final class MysqlConversationStore implements ConversationStore {
    record Window(long version, long before) {}
    interface Access {
        <T> T read(Supplier<T> action);
        void session(UUID id, AccessScope owner);
        Window window(RunState run);
    }
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RedisMemoryCache cache;
    private final Access access;
    private final MemoryBuilder memoryBuilder = new MemoryBuilder();
    MysqlConversationStore(JdbcTemplate db, ObjectMapper json, RedisMemoryCache cache, Access access) {
        this.db = db; this.json = json; this.cache = cache; this.access = access;
    }
    private <T> T decode(Object value, Class<T> type) {
        try { return json.readValue(value.toString(), type); }
        catch (Exception e) { throw new AgentException("STORAGE_CORRUPT", "Stored record is inconsistent"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new AgentException("STORAGE_ENCODING_ERROR", "Unsupported persistent payload"); }
    }
    private String hash(String value) { return FileObjectStore.hash(value.getBytes(StandardCharsets.UTF_8)); }
    private long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Timestamp stamp ? stamp.toInstant() : ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }
    List<MemoryTypes.Message> history(UUID sid, AccessScope owner, long before) {
        List<Map<String, Object>> turns = db.queryForList("SELECT run_id,access_scope_json FROM agent_run WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND history_visible=1 AND turn_seq<=? AND data_expires_at>UTC_TIMESTAMP(6) ORDER BY turn_seq DESC LIMIT 10", owner.tenantId(), owner.userId(), sid.toString(), before);
        List<MemoryTypes.Message> messages = new ArrayList<>();
        for (var turn : turns) {
            boolean allowed = owner.videoIds().containsAll(decode(turn.get("access_scope_json"), AccessScope.class).videoIds());
            messages.addAll(db.query("SELECT message_id,run_id,message_seq,role,content,created_at FROM agent_message WHERE tenant_id=? AND owner_user_id=? AND run_id=? ORDER BY message_seq",
                    (rs, n) -> new MemoryTypes.Message(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getLong(3), rs.getString(4).toLowerCase(Locale.ROOT),
                            allowed || "user".equalsIgnoreCase(rs.getString(4)) ? rs.getString(5) : "[先前视频结果当前无权限，已隐藏]",rs.getTimestamp(6).toInstant()), owner.tenantId(), owner.userId(), turn.get("run_id")));
        }
        return messages.stream().sorted(Comparator.comparingLong(MemoryTypes.Message::sequence)).toList();
    }
    @Override public MemoryTypes.View memory(RunState run) {
        return access.read(() -> {
            var window = access.window(run);
            long version = window.version(), before = window.before();
            String key = cacheKey(run.owner, run.sessionId);
            var cached = cache.get(key, version, before);
            List<MemoryTypes.Message> messages; MemoryTypes.Focus focus;
            if (cached == null) {
                messages = history(run.sessionId, run.owner, before);
                var stored = db.queryForList("SELECT focus_json FROM agent_conversation_focus WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND turn_seq<=?",String.class,run.owner.tenantId(),run.owner.userId(),run.sessionId.toString(),before);
                focus = stored.isEmpty() ? null : decode(stored.get(0),MemoryTypes.Focus.class);
                cache.put(key,new RedisMemoryCache.Window(version,before,cacheExpiry(run.sessionId,run.owner,before),messages,focus));
            } else { messages = cached.messages(); focus = cached.focus(); }
            return memoryBuilder.build(version, messages).withContext(run.pageContext(),focus);

        });
    }
    @Override public void focus(RunState run,MemoryTypes.Focus value) {
        access.read(() -> { access.session(run.sessionId,run.owner); return null; });
        db.update("INSERT INTO agent_conversation_focus(tenant_id,owner_user_id,session_id,turn_seq,focus_json) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                +"focus_json=IF(turn_seq<=VALUES(turn_seq),VALUES(focus_json),focus_json),turn_seq=GREATEST(turn_seq,VALUES(turn_seq))",
                run.owner.tenantId(),run.owner.userId(),run.sessionId.toString(),run.turnSeq,encode(value));
        cache.evict(cacheKey(run.owner,run.sessionId));
    }
    String cacheKey(AccessScope owner, UUID session) { return RedisMemoryCache.key(owner.tenantId(), owner.userId() + ":" + hash(encode(new TreeSet<>(owner.videoIds()))), session); }
    Instant cacheExpiry(UUID sid, AccessScope owner, long before) {
        Timestamp oldest = db.queryForObject("SELECT MIN(data_expires_at) FROM (SELECT data_expires_at FROM agent_run WHERE tenant_id=? AND owner_user_id=? AND session_id=? AND history_visible=1 AND turn_seq<=? AND data_expires_at>UTC_TIMESTAMP(6) ORDER BY turn_seq DESC LIMIT 10) recent", Timestamp.class, owner.tenantId(), owner.userId(), sid.toString(), before);
        Instant ttl = Instant.now().plus(Duration.ofHours(24)); return oldest != null && oldest.toInstant().isBefore(ttl) ? oldest.toInstant() : ttl;
    }
    @Override public List<MemoryTypes.Message> messages(UUID sid, AccessScope owner, long before) {
        return access.read(() -> {
            access.session(sid, owner); if (before < 1) throw new AgentException("INVALID_CURSOR", "Invalid message cursor");
            var rows = db.queryForList("SELECT m.*,r.access_scope_json FROM agent_message m JOIN agent_run r ON r.tenant_id=m.tenant_id AND r.run_id=m.run_id WHERE m.tenant_id=? AND m.owner_user_id=? AND m.session_id=? AND m.message_seq<? AND r.data_expires_at>UTC_TIMESTAMP(6) ORDER BY m.message_seq DESC LIMIT 50", owner.tenantId(), owner.userId(), sid.toString(), before);
            List<MemoryTypes.Message> result = new ArrayList<>();
            for (var row : rows) {
                boolean allowed = owner.videoIds().containsAll(decode(row.get("access_scope_json"),AccessScope.class).videoIds());
                String role = row.get("role").toString().toLowerCase(Locale.ROOT);
                result.add(new MemoryTypes.Message(UUID.fromString(row.get("message_id").toString()),UUID.fromString(row.get("run_id").toString()),number(row,"message_seq"),role,
                        allowed || role.equals("user") ? row.get("content").toString() : "[先前视频结果当前无权限，已隐藏]",instant(row,"created_at")));
            }
            return result.stream().sorted(Comparator.comparingLong(MemoryTypes.Message::sequence)).toList();
        });
    }
}
