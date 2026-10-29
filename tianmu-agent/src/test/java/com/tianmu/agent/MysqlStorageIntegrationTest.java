package com.tianmu.agent;

import com.tianmu.agent.adapters.storage.*;
import com.tianmu.agent.core.*;
import com.tianmu.agent.core.AgentTypes.*;
import com.tianmu.agent.core.PreparationTypes.*;
import com.tianmu.agent.runtime.RunState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real MySQL/Redis integration. Refuses URLs outside a database ending in _it. */
@EnabledIfEnvironmentVariable(named="AGENT_STORAGE_IT", matches="true")
class MysqlStorageIntegrationTest {
    @TempDir Path directory;
    HikariDataSource source; MysqlRunRepository repository; RedisMemoryCache cache; JdbcTemplate db;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    AccessScope owner; final List<UUID> sessions = new ArrayList<>();
    @BeforeEach void open() {
        String url = System.getenv("AGENT_JDBC_URL"); assertTrue(url.matches("jdbc:mysql://[^/]+/[^?]*_it(?:\\?.*)?"), "Integration requires a dedicated _it database");
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername(System.getenv("AGENT_DB_USER"));
        config.setPassword(System.getenv("AGENT_DB_PASSWORD")); config.setMaximumPoolSize(5); config.setConnectionInitSql("SET time_zone = '+00:00'");
        source = new HikariDataSource(config); db = new JdbcTemplate(source);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").cleanDisabled(true).load().migrate();
        cache = new RedisMemoryCache(mapper, true, System.getenv("AGENT_REDIS_HOST"), Integer.parseInt(System.getenv("AGENT_REDIS_PORT")), "");
        repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16);
        owner = new AccessScope("it_" + UUID.randomUUID().toString().substring(0, 8), "alice", Set.of(1001L, 1002L));
    }
    @AfterEach void close() {
        if (repository != null) {
            for (UUID session : sessions) { try { repository.deleteSession(session, owner); } catch (Exception ignored) {} }
            repository.garbageCollect(); repository.close();
        }
        if (cache != null) cache.close(); if (source != null) source.close();
    }
    RunState create(String query) { RunState run = repository.create(query, owner, null, UUID.randomUUID().toString(), null).run(); sessions.add(run.sessionId); return run; }
    @Test void conversationFocusForeignKeyColumnsMatchSessionCollations(){
        for(String column:List.of("tenant_id","owner_user_id","session_id")){
            String sessionCollation=db.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_session' AND COLUMN_NAME=?",String.class,column);
            String focusCollation=db.queryForObject("SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='agent_conversation_focus' AND COLUMN_NAME=?",String.class,column);
            assertNotNull(sessionCollation);assertEquals(sessionCollation,focusCollation,column);
        }
    }
    void faq(RunState run) {
        run.start(); run.prepared(new Result(Route.FAQ, new PreparedRequest(run.message, run.query(), Emotion.UNKNOWN, Set.of()), new FaqAnswer("fixture", "1", "approved"), null));
        assertTrue(run.succeed(run.reserveFinalResponse(), new Answer("approved", List.of()), StopReason.FAQ_ANSWER));
    }
    @Test void pageAndConversationFocusSurviveReloadWithoutMixingUserTextWithMetadata() {
        var page=new RequestContext("1001","author supplied title",1500L);
        var run=repository.create("下一个视频给我一点灵感",owner,null,UUID.randomUUID().toString(),null,page).run();sessions.add(run.sessionId);
        var focus=new MemoryTypes.Focus(run.message,run.id,"1001");repository.focus(run,focus);faq(run);
        var loaded=repository.load(run.id,owner);assertEquals(page,loaded.pageContext());assertEquals(run.message,loaded.query());
        var next=repository.create("就是现在的视频",owner,run.sessionId,UUID.randomUUID().toString(),null,page).run();
        assertEquals(focus,repository.memory(next).focus());
        repository.focus(next,new MemoryTypes.Focus("新目标",next.id,"1001"));
        repository.focus(run,focus);
        assertEquals(2L,db.queryForObject("SELECT turn_seq FROM agent_conversation_focus WHERE tenant_id=? AND session_id=?",Long.class,owner.tenantId(),run.sessionId.toString()));
        faq(next);
    }
    @Test void creatorDraftCasSurvivesReloadAndSessionDeletionRemovesIt() {
        var run = create("creator draft");
        var store = new MysqlCreatorDraftStore(source,mapper);
        var draft = new com.tianmu.agent.interaction.CreatorDraft(run.id,owner.tenantId(),owner.userId(),"1001","COMMENT","DRAFT",
                List.of(new com.tianmu.agent.interaction.CreatorDraft.Item("123","作者留言",null)));
        store.create(draft);
        assertEquals(draft,new MysqlCreatorDraftStore(source,mapper).get(run.id));
        var frozen = draft.with("PUBLISHING",draft.items());
        assertTrue(store.replace(draft,frozen)); assertFalse(store.replace(draft,frozen));
        assertEquals(frozen,store.get(run.id));
        faq(run); repository.deleteSession(run.sessionId,owner); repository.garbageCollect();
        assertNull(store.get(run.id));
    }
    @Test void retryIsIdempotentAndOwnershipIsCheckedOnRead() {
        String key = UUID.randomUUID().toString(); var first = repository.create("question", owner, null, key, null); sessions.add(first.run().sessionId);
        assertTrue(first.fresh()); assertEquals(first.run().id, repository.create("question", owner, null, key, null).run().id);
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(AgentException.class, () -> repository.create("different", owner, null, key, null)).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(AgentException.class, () -> repository.load(first.run().id, new AccessScope(owner.tenantId(), "bob", owner.videoIds()))).code());
        faq(first.run()); assertEquals(Status.SUCCEEDED, repository.load(first.run().id, owner).snapshot().status());
    }
    @Test void actualSqlFailureRollsBackMessageEventsAuditAndTerminalState() {
        RunState run = create("rollback"); run.start(); String trigger = "it_done_" + UUID.randomUUID().toString().replace("-", "");
        db.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON agent_run_event FOR EACH ROW BEGIN IF NEW.run_id='" + run.id + "' AND NEW.event_type='DONE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='integration terminal fault'; END IF; END");
        try {
            assertEquals("STORAGE_UNAVAILABLE", assertThrows(AgentException.class, () -> run.succeed(new Answer("candidate", List.of()))).code());
            assertEquals(Status.RUNNING, run.snapshot().status()); assertEquals(Status.RUNNING, repository.load(run.id, owner).snapshot().status());
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_message WHERE run_id=? AND role='ASSISTANT'", Integer.class, run.id.toString()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_run_event WHERE run_id=? AND event_type IN ('ANSWER','DONE')", Integer.class, run.id.toString()));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_audit_event WHERE run_id=? AND action='RUN_COMMITTED'", Integer.class, run.id.toString()));
        } finally { db.execute("DROP TRIGGER " + trigger); }
        run.cancel(); assertEquals(Status.CANCELLED, repository.load(run.id, owner).snapshot().status());
    }
    @Test void pageContextAndOriginalWordsSurviveReloadAndIdempotency() {
        String key = "context-" + UUID.randomUUID();
        var page = new RequestContext("1001");
        RunState run = repository.create("昨天有多少条弹幕？", owner, null, key, null, page).run();
        sessions.add(run.sessionId);
        assertEquals("昨天有多少条弹幕？", run.message);
        assertEquals(run.message,run.query());assertEquals("1001",run.pageContext().videoId());
        assertEquals("1001", db.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.context.videoId')) FROM agent_run WHERE run_id=?", String.class, run.id.toString()));
        faq(run);
        assertFalse(repository.create("昨天有多少条弹幕？", owner, null, key, null, page).fresh());
        assertThrows(AgentException.class, () -> repository.create("昨天有多少条弹幕？", owner, null, key, null, new RequestContext("1002")));
        repository.close(); repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16);
        RunState restored = repository.load(run.id, owner);
        assertEquals(run.message, restored.message);
        assertEquals(run.query(), restored.query());
        assertEquals("1001",restored.pageContext().videoId());
    }
    @Test void evidenceArtifactAndEventReplaySurviveRepositoryReconstruction() throws Exception {
        RunState run = create("metric"); run.start();
        var data = new MetricData(1001L, run.businessDate.minusDays(1), "bullet_count", 128, "v1", "integration-fixture", Instant.now(), "Asia/Shanghai", true, true);
        var evidence = new Evidence("ev_" + UUID.randomUUID(), run.id, owner, data); run.execution().evidence().put(evidence);
        run.succeed(new Answer("128", List.of(evidence.id())));
        assertEquals(1, repository.artifacts(run.id, owner).size());
        String artifact = repository.artifacts(run.id, owner).get(0).get("artifactId").toString();
        assertEquals(128, mapper.readTree(repository.artifact(run.id, artifact, owner)).get("value").asInt());
        repository.close(); repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16);
        RunState restored = repository.load(run.id, owner);
        assertEquals(128, restored.execution().evidence().get(evidence.id()).data().value());
        assertEquals(1, restored.events().after(0).events().stream().filter(e -> e.type().equals("DONE")).count());
        assertEquals(Status.SUCCEEDED, restored.snapshot().status());
    }
    @Test void aNarrowedScopeCannotReuseTheBroaderCachedHistory() {
        RunState original = create("private historical fixture"); faq(original); repository.deliverOutbox();
        var narrow = new AccessScope(owner.tenantId(), owner.userId(), Set.of(1002L));
        RunState next = repository.create("new", narrow, original.sessionId, null, null).run();
        var messages=repository.memory(next).recent();assertEquals(2,messages.size());
        assertEquals("user",messages.get(0).role());assertEquals(original.message,messages.get(0).text());
        assertEquals("assistant",messages.get(1).role());assertEquals("[先前视频结果当前无权限，已隐藏]",messages.get(1).text());
        assertTrue(messages.stream().noneMatch(message->message.text().equals("approved")));
        assertEquals(messages,repository.memory(next).recent());
        assertEquals("FORBIDDEN", assertThrows(AgentException.class, () -> repository.load(original.id, narrow)).code());
        next.cancel();
    }
    @Test void secondWriterIsRejectedAndDeletingSessionRemovesPrivateObjects() throws Exception {
        assertThrows(IllegalStateException.class, () -> new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16));
        RunState run = create("delete"); run.start();
        String id = "ev_" + UUID.randomUUID();
        run.execution().evidence().put(new Evidence(id, run.id, owner, new MetricData(1001L, run.businessDate.minusDays(1), "bullet_count", 1, "v1", "fixture", Instant.now(), "Asia/Shanghai", true, true)));
        run.succeed(new Answer("1", List.of(id)));
        try (var files = java.nio.file.Files.list(directory)) { assertEquals(1, files.count()); }
        repository.deleteSession(run.sessionId, owner);
        assertEquals("SESSION_NOT_FOUND", assertThrows(AgentException.class, () -> repository.load(run.id, owner)).code());
        repository.garbageCollect(); assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM agent_run WHERE run_id=?", Integer.class, run.id.toString()));
        try (var files = java.nio.file.Files.list(directory)) { assertEquals(0, files.count()); }
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM agent_audit_event WHERE tenant_id=? AND session_id=? AND action='SESSION_DELETE_REQUESTED'", Integer.class, owner.tenantId(), run.sessionId.toString()));
    }
    @Test void liveEvidenceAndHtmlReportSurviveReloadAndAreDeletedWithSession() throws Exception {
        RunState run=create("live report"); run.start();
        var q=new com.tianmu.agent.core.DanmakuTypes.Query(1001,run.businessDate.minusDays(1).atStartOfDay(),run.businessDate.atStartOfDay());
        var bins=new java.util.ArrayList<com.tianmu.agent.core.DanmakuTypes.Bucket>();
        for(int i=0;i<12;i++) bins.add(new com.tianmu.agent.core.DanmakuTypes.Bucket(i,0));
        var data=new com.tianmu.agent.core.DanmakuTypes.Dataset(q,0,0,bins,List.of(),Instant.now(),"fixture",true);
        String id="ev_"+UUID.randomUUID();
        run.execution().evidence().put(new Evidence(id,run.id,owner,null,data));
        assertEquals(data,repository.load(run.id,owner).execution().evidence().get(id).analysis());
        byte[] html="<!doctype html><html>report</html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var ref=repository.saveReport(run.id,owner,id,html);
        run.emit("ARTIFACT_READY",Map.of("artifact_id",ref.artifactId(),"url",ref.url(),"media_type","text/html"));
        run.succeed(new Answer("report",List.of(id)));
        repository.close();
        repository=new MysqlRunRepository(source,mapper,cache,new FileObjectStore(directory),Duration.ofSeconds(30),16);
        assertArrayEquals(html,repository.report(run.id,owner,ref.artifactId()));
        assertEquals(1,repository.load(run.id,owner).events().after(0).events().stream().filter(e->e.type().equals("ARTIFACT_READY")).count());
        assertThrows(AgentException.class,()->repository.report(run.id,new AccessScope(owner.tenantId(),"bob",owner.videoIds()),ref.artifactId()));
        repository.deleteSession(run.sessionId,owner); repository.garbageCollect();
        try(var files=java.nio.file.Files.list(directory)) { assertEquals(0,files.count()); }
    }
    @Test void interruptedExecutionBecomesRunnerLostAndTheOldWriterCannotPublish() {
        RunState run = create("interrupted"); run.start(); repository.close();
        repository = new MysqlRunRepository(source, mapper, cache, new FileObjectStore(directory), Duration.ofSeconds(30), 16);
        Snapshot recovered = repository.load(run.id, owner).snapshot();
        assertEquals(Status.FAILED, recovered.status()); assertEquals("RUNNER_LOST", recovered.errorCode());
        assertThrows(AgentException.class, () -> run.succeed(new Answer("late", List.of())));
        assertNull(repository.load(run.id, owner).snapshot().answer());
        assertEquals(1, repository.load(run.id, owner).events().after(0).events().stream().filter(e -> e.type().equals("DONE")).count());
    }
    @Test void reportCacheRanksSurviveNewAdaptersAndExpiredRowsCanRestartAtALowerRank(){
        var store=new MysqlAnalysisCacheStore(source);String key=UUID.randomUUID().toString().replace("-","").repeat(2);
        try{
            store.put(key,new com.tianmu.agent.runtime.store.AnalysisCacheStore.Entry(80,"accepted",Instant.now().plusSeconds(60)));
            store.put(key,new com.tianmu.agent.runtime.store.AnalysisCacheStore.Entry(40,"slower",Instant.now().plusSeconds(60)));
            assertEquals("accepted",new MysqlAnalysisCacheStore(source).get(key).json());
            db.update("UPDATE agent_report_cache SET expires_at=? WHERE cache_key=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),key);
            store.put(key,new com.tianmu.agent.runtime.store.AnalysisCacheStore.Entry(20,"fresh",Instant.now().plusSeconds(60)));
            assertEquals(20,store.get(key).rank());assertEquals("fresh",store.get(key).json());
        }finally{db.update("DELETE FROM agent_report_cache WHERE cache_key=?",key);}
    }
    @Test void qaAdmissionIsDurableAndSessionReplayDoesNotInflateCounts()throws Exception{
        var policy=new com.tianmu.agent.faq.QaPolicy(true,2,1,30,7,256);
        var qa=new com.tianmu.agent.faq.QaCatalog(new MysqlQaStore(source,mapper),policy);
        String question="如何设计选题主题",answer="先确定面向的观众，再整理可以持续观察和讨论的选题方向。";UUID session=UUID.randomUUID();
        String ownerText=owner.tenantId().length()+":"+owner.tenantId()+owner.userId().length()+":"+owner.userId();
        String ownerKey=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ownerText.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        try{
            qa.observe(owner,session,UUID.randomUUID(),question,answer,Instant.now());qa.observe(owner,session,UUID.randomUUID(),question,answer,Instant.now());
            assertEquals(1,qa.list(owner).get(0).sessions());
            qa.observe(owner,UUID.randomUUID(),UUID.randomUUID(),question,answer,Instant.now());
            var restarted=new com.tianmu.agent.faq.QaCatalog(new MysqlQaStore(source,mapper),policy);
            assertEquals(answer,restarted.find(new Input(question,LocalDate.now(),owner)).orElseThrow().text());
        }finally{db.update("DELETE FROM agent_qa_catalog WHERE owner_key=?",ownerKey);}
    }
    @Test void reportLeasesCoordinateSeparateAdaptersAndCanRecoverAfterExpiry(){
        var first=new MysqlAnalysisCacheStore(source);var second=new MysqlAnalysisCacheStore(source);
        String key=UUID.randomUUID().toString().replace("-","").repeat(2);UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try{
            assertTrue(first.acquire(key,a));assertFalse(second.acquire(key,b));second.release(b);assertFalse(second.acquire(key,b));
            db.update("UPDATE agent_report_leases SET valid_until=? WHERE cache_key=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),key);
            assertTrue(second.acquire(key,b));first.release(a);assertFalse(first.acquire(key,a));
        }finally{db.update("DELETE FROM agent_report_leases WHERE cache_key=?",key);}
    }
}
