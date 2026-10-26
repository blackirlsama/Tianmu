package com.tianmu.agent.adapters.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.runtime.RunState;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MysqlConversationStoreTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AccessScope owner = new AccessScope("tenant", "alice", Set.of(1001L));
    private MysqlConversationStore.Access access(boolean terminal) {
        return new MysqlConversationStore.Access() {
            public <T> T read(Supplier<T> action) { return action.get(); }
            public void session(UUID id, AccessScope scope) {
                if (!scope.tenantId().equals(owner.tenantId()) || !scope.userId().equals(owner.userId())) throw new AgentException("SESSION_NOT_FOUND", "Missing");
            }
            public MysqlConversationStore.Window window(RunState run) {
                session(run.sessionId, run.owner);
                if (terminal) throw new AgentException("RUN_TERMINATED", "Terminal");
                return new MysqlConversationStore.Window(2, 1);
            }
        };
    }
    private Map<String, Object> message(Set<Long> videos, long sequence) throws Exception {
        return Map.of("message_id", UUID.randomUUID().toString(), "run_id", UUID.randomUUID().toString(), "message_seq", sequence,
                "role", "user", "content", "private message", "created_at", Timestamp.from(Instant.now()),
                "access_scope_json", json.writeValueAsString(new AccessScope("tenant", "alice", videos)));
    }
    @Test void messageReadFiltersRevokedVideoScopesAndChecksSessionBeforeDatabaseRead() throws Exception {
        var db = mock(JdbcTemplate.class); var cache = mock(RedisMemoryCache.class);
        var store = new MysqlConversationStore(db, json, cache, access(false)); UUID session = UUID.randomUUID();
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(message(Set.of(1001L), 1), message(Set.of(1002L), 2)));
        var result = store.messages(session, owner, 10);
        assertEquals(1, result.size()); assertEquals(1, result.get(0).sequence());
        clearInvocations(db);
        assertEquals("SESSION_NOT_FOUND", assertThrows(AgentException.class, () -> store.messages(session,
                new AccessScope("tenant", "bob", Set.of(1001L)), 10)).code());
        assertEquals("INVALID_CURSOR", assertThrows(AgentException.class, () -> store.messages(session, owner, 0)).code());
        verifyNoInteractions(db);
    }
    @Test void cachedMemoryCannotBypassTerminalAuthorityAndCacheKeysIncludeCurrentScope() {
        var db = mock(JdbcTemplate.class); var cache = mock(RedisMemoryCache.class);
        var store = new MysqlConversationStore(db, json, cache, access(true));
        RunState run = new RunState(owner, "query");
        assertEquals("RUN_TERMINATED", assertThrows(AgentException.class, () -> store.memory(run)).code());
        verifyNoInteractions(cache, db);
        UUID session = UUID.randomUUID();
        assertNotEquals(store.cacheKey(owner, session), store.cacheKey(new AccessScope("tenant", "alice", Set.of(1002L)), session));
        assertNotEquals(store.cacheKey(owner, session), store.cacheKey(new AccessScope("tenant", "bob", Set.of(1001L)), session));
    }
    @Test void malformedStoredScopeFailsClosedInsteadOfReturningMessage() {
        var db = mock(JdbcTemplate.class);
        var store = new MysqlConversationStore(db, json, mock(RedisMemoryCache.class), access(false));
        when(db.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of("access_scope_json", "invalid")));
        assertEquals("STORAGE_CORRUPT", assertThrows(AgentException.class, () -> store.messages(UUID.randomUUID(), owner, 10)).code());
    }
}
