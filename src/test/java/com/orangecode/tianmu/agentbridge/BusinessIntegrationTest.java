package com.orangecode.tianmu.agentbridge;

import com.orangecode.tianmu.model.dto.bullet.SendBulletRequest;
import com.orangecode.tianmu.service.BulletIngestionService;
import com.orangecode.tianmu.utils.JwtUtil;
import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="BUSINESS_IT", matches="true")
class BusinessIntegrationTest {
    @Test void jointSnapshotKeepsSourceCountsAndSeparatesAuthorsBeforeCompression() {
        db.update("DELETE FROM comment WHERE video_id=1001");
        bullet(5001,start());
        db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,playback_time,create_time) VALUES(5002,1001,882,'fixture',1,?)",Timestamp.from(start()));
        comment(5001,1001,882L,null,"观众问题",start());
        comment(5003,1001,881L,5001L,"作者补充",start().plusSeconds(1));
        var config=new BridgeProperties("tianmu",Set.of("normal"),"Asia/Shanghai",5);
        var service=new BusinessAudienceAnalysis(db,config,new BusinessDanmakuAnalysis(db,config),new BusinessCommentAnalysis(db,config));
        var snapshot=service.query(principal(),1001,day.atStartOfDay(),day.plusDays(1).atStartOfDay());
        assertEquals(2,snapshot.danmaku().totalMessages());assertEquals(2,snapshot.danmaku().audience().corpus().size());
        assertEquals(1,snapshot.danmaku().audience().corpus().stream().filter(BusinessDanmakuAnalysis.TextGroup::author).count());
        assertEquals(2,snapshot.comments().totalComments());assertEquals(1,snapshot.comments().audienceComments());
        assertEquals(1,snapshot.uniqueAudience());
    }
    @Test void commentThreadsPreserveIdenticalRepliesAndOldAncestorsWithoutCountingThem() {
        db.update("DELETE FROM comment WHERE video_id IN (1001,1002)");
        comment(4001,1001,881L,null,"作者的问题",start().minusSeconds(1));
        comment(4002,1001,882L,4001L,"同意",start());
        comment(4003,1001,882L,4002L,"同意",start().plusSeconds(1));
        comment(4004,1001,881L,null,"作者补充",start().plusSeconds(2));
        comment(4005,1002,882L,null,"其他视频",start().plusSeconds(2));
        comment(4006,1001,882L,4005L,"跨视频父评论",start().plusSeconds(3));
        comment(4007,1001,882L,null,"右端点不计入",start().plusSeconds(86400));
        var service = new BusinessCommentAnalysis(db,new BridgeProperties("tianmu",Set.of("normal"),"Asia/Shanghai",5));
        var result = service.query(principal(),1001,day.atStartOfDay(),day.plusDays(1).atStartOfDay());
        assertEquals(4,result.totalComments()); assertEquals(3,result.audienceComments()); assertEquals(1,result.uniqueAudience());
        assertEquals(5,result.comments().size());
        assertEquals(2,result.comments().stream().filter(c->c.text().equals("同意")).count());
        assertFalse(result.comments().stream().filter(c->c.id().equals("4001")).findFirst().orElseThrow().inPeriod());
        assertEquals("4002",result.comments().stream().filter(c->c.id().equals("4003")).findFirst().orElseThrow().parentId());
        assertTrue(result.comments().stream().filter(c->c.id().equals("4006")).findFirst().orElseThrow().parentUnavailable());
        assertFalse(result.comments().stream().anyMatch(c->c.id().equals("4005")));
        assertEquals(403,assertThrows(BridgeException.class,()->service.query(principal(),1002,day.atStartOfDay(),day.plusDays(1).atStartOfDay())).status());
    }
    private void comment(long id,long video,Long user,Long parent,String text,Instant at) {
        db.update("INSERT INTO comment(comment_id,video_id,user_id,parent_comment_id,content,create_time) VALUES(?,?,?,?,?,?)",id,video,user,parent,text,Timestamp.from(at));
    }
    @Test void completeCorpusContainsAllTwoHundredTextsWithoutClippingAndUsesShortVideoBins(){
        db.update("UPDATE video SET duration=31.698 WHERE video_id=1001");
        db.update("UPDATE user SET nickname='模拟·布丁' WHERE user_id=881");
        for(int i=0;i<200;i++)db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,color,playback_time,create_time) VALUES(?,1001,881,?,'white',?,?)",
                19000+i,i==0?"绒毛细节".repeat(45):"观众希望继续看到小鸭的日常片段"+i,(i*73%316)/10.0,
                Timestamp.from(day.minusDays(i%7).atTime(10,i%60).atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        var service=new BusinessDanmakuAnalysis(db,new BridgeProperties("tianmu",Set.of("normal"),"Asia/Shanghai",5));
        var result=service.query(principal(),1001,day.minusDays(6).atStartOfDay(),day.plusDays(1).atStartOfDay());
        assertEquals(200,result.totalMessages());assertEquals(200,result.audience().corpusMessages());assertEquals(200,result.audience().corpus().size());
        assertTrue(result.samples().isEmpty());assertTrue(result.audience().corpus().stream().noneMatch(BusinessDanmakuAnalysis.TextGroup::truncated));
        assertTrue(result.audience().corpus().stream().anyMatch(g->g.text().equals("绒毛细节".repeat(45))));
        assertTrue(result.audience().playback().size()>8);assertTrue(result.audience().playback().stream().allMatch(p->p.endMs()-p.startMs()<=3000));
        assertEquals("模拟·布丁",result.audience().interactions().get(0).nickname());assertEquals(7,result.audience().interactions().get(0).activeDays());
    }
    @Test void sevenDayRoomOverviewUsesExactGroupsPositionsAndInteractionRanking() {
        db.update("UPDATE video SET duration=20 WHERE video_id=1001");
        String[] text={"尺寸？","尺寸？","联系13812345678","颜色？"};
        long[] users={881,882,881,881};double[] positions={2.5,5,9999,15};
        for(int i=0;i<4;i++) {
            Instant at=day.minusDays(i==0?2:0).atTime(12,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant();
            db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,playback_time,create_time) VALUES(?,1001,?,?,?,?)",7001+i,users[i],text[i],positions[i],Timestamp.from(at));
        }
        db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,playback_time,create_time) VALUES(7005,1001,-7,'',-1,?)",Timestamp.from(day.atTime(13,0).atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        var service=new BusinessDanmakuAnalysis(db,new BridgeProperties("tianmu",Set.of("normal"),"Asia/Shanghai",5));
        var result=service.query(principal(),1001,day.minusDays(6).atStartOfDay(),day.plusDays(1).atStartOfDay());
        assertEquals(5,result.totalMessages());assertEquals(2,result.uniqueSenders());
        var a=result.audience();assertEquals(5,a.corpusMessages());assertEquals(5,a.corpus().size());
        assertEquals(2,a.unknownPlayback());assertEquals(3,a.playback().stream().mapToLong(BusinessDanmakuAnalysis.Position::count).sum());
        assertEquals(10,a.playback().size());assertTrue(a.playback().stream().anyMatch(p->p.count()==0));
        assertTrue(a.playback().stream().allMatch(p->p.endMs()<=20000));
        assertEquals("881",a.interactions().get(0).userId());assertEquals(3,a.interactions().get(0).messages());assertEquals(2,a.interactions().get(0).activeDays());
        var sizes=a.corpus().stream().filter(g->g.text().equals("尺寸？")).toList();
        assertEquals(2,sizes.stream().mapToLong(BusinessDanmakuAnalysis.TextGroup::count).sum());
        assertEquals(2,sizes.stream().flatMap(g->g.trend().stream()).mapToLong(Long::longValue).sum());
        assertEquals(1,sizes.stream().filter(BusinessDanmakuAnalysis.TextGroup::author).count());
        assertTrue(a.corpus().stream().noneMatch(g->g.text().contains("13812345678")));
        db.update("UPDATE video SET duration=NULL WHERE video_id=1001");
        var unknown=service.query(principal(),1001,day.minusDays(6).atStartOfDay(),day.plusDays(1).atStartOfDay()).audience();
        assertEquals(5,unknown.unknownPlayback());assertTrue(unknown.playback().isEmpty());assertTrue(unknown.corpus().stream().allMatch(g->g.playbackMs()==null));
        assertEquals(403,assertThrows(BridgeException.class,()->service.query(principal(),1002,day.minusDays(6).atStartOfDay(),day.plusDays(1).atStartOfDay())).status());
    }
    HikariDataSource source; JdbcTemplate db; LettuceConnectionFactory factory; StringRedisTemplate redis;
    BusinessAuthentication auth; BusinessMetrics metrics; TransactionTemplate transaction;
    final LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
    String alice, bob;
    @BeforeEach void setup() throws Exception {
        String url = System.getenv("BUSINESS_JDBC_URL");
        assertTrue(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+_it(?:\\?.*)?"), "Dedicated local _it database required");
        HikariConfig config = new HikariConfig(); config.setJdbcUrl(url); config.setUsername(System.getenv("BUSINESS_DB_USER"));
        config.setPassword(System.getenv("BUSINESS_DB_PASSWORD")); config.setMaximumPoolSize(4); config.setConnectionInitSql("SET time_zone='+00:00'");
        source = new HikariDataSource(config); db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new ClassPathResource("business-schema.sql")).execute(source);
        if(db.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='video' AND COLUMN_NAME='duration'",Integer.class)==0)
            db.execute("ALTER TABLE video ADD COLUMN duration DOUBLE DEFAULT 180");
        if(db.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user' AND COLUMN_NAME='nickname'",Integer.class)==0)
            db.execute("ALTER TABLE user ADD COLUMN nickname VARCHAR(255) NOT NULL DEFAULT ''");
        db.update("DELETE FROM bullet WHERE video_id IN (1001,1002,1003)");
        db.update("DELETE FROM video_stats WHERE video_id IN (1001,1002,1003)");
        for (long id : List.of(881L,882L)) db.update("INSERT INTO user(user_id,state,is_delete) VALUES(?,'normal',0) ON DUPLICATE KEY UPDATE state='normal',is_delete=0", id);
        for (long video : List.of(1001L,1002L,1003L)) db.update("INSERT INTO video(video_id,user_id,is_delete) VALUES(?,?,0) ON DUPLICATE KEY UPDATE user_id=VALUES(user_id),is_delete=0", video, video == 1002 ? 882 : 881);
        db.update("UPDATE video SET duration=180 WHERE video_id IN (1001,1002,1003)");
        factory = new LettuceConnectionFactory(System.getenv("BUSINESS_REDIS_HOST"), Integer.parseInt(System.getenv("BUSINESS_REDIS_PORT")));
        factory.afterPropertiesSet(); redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
        alice = JwtUtil.generate("881"); bob = JwtUtil.generate("882");
        redis.opsForValue().set("881", alice, Duration.ofMinutes(5)); redis.opsForValue().set("882", bob, Duration.ofMinutes(5));
        var properties = new BridgeProperties("tianmu", Set.of("normal"), "Asia/Shanghai", 2);
        auth = new BusinessAuthentication(db, redis, properties); metrics = new BusinessMetrics(db, properties);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }
    @AfterEach void close() { if (factory != null) { redis.delete(List.of("881","882")); factory.destroy(); } if (source != null) source.close(); }
    BusinessAuthentication.Principal principal() { return auth.authenticate("Bearer " + alice); }
    Instant start() { return day.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(); }
    void bullet(long id, Instant time) {
        db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,color,playback_time,create_time) VALUES(?,1001,881,'fixture','ffffff',1,?)", id, Timestamp.from(time));
    }
    @Test void liveAnalysisCountsRangeBoundariesAndDoesNotExposeUserIdentifiers() {
        var service = new BusinessDanmakuAnalysis(db,new BridgeProperties("tianmu",Set.of("normal"),"Asia/Shanghai",3));
        LocalDateTime start=day.atTime(20,0), end=day.atTime(22,0);
        long seed=800000;
        for(int i=0;i<10;i++) db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,playback_time,create_time) VALUES(?,1001,881,?,0,?)",
                seed+i,"什么时候发货？13812345678",Timestamp.from(start.plusMinutes(i).atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,playback_time,create_time) VALUES(?,1001,881,'outside',0,?)",seed+10,Timestamp.from(end.atZone(ZoneId.of("Asia/Shanghai")).toInstant()));
        var result=service.query(principal(),1001,start,end);
        assertEquals(10,result.totalMessages()); assertEquals(1,result.uniqueSenders());
        assertEquals(10,result.buckets().stream().mapToLong(BusinessDanmakuAnalysis.Bucket::count).sum());
        assertTrue(result.samples().size()<=48); assertTrue(result.samples().stream().allMatch(s->!s.text().contains("13812345678")));
        assertEquals(403,assertThrows(BridgeException.class,()->service.query(principal(),1002,start,end)).status());
    }
    @Test void realJwtAndRedisSessionAreBothRequired() {
        assertEquals("881", principal().userId());
        assertEquals("UNAUTHORIZED", assertThrows(BridgeException.class, () -> auth.authenticate("invalid")).code());
        redis.delete("881"); assertEquals(401, assertThrows(BridgeException.class, this::principal).status());
    }
    @Test void disabledAndDeletedUsersCannotAuthenticate() {
        db.update("UPDATE user SET state='disabled' WHERE user_id=881"); assertThrows(BridgeException.class, this::principal);
        db.update("UPDATE user SET state='normal',is_delete=1 WHERE user_id=881"); assertThrows(BridgeException.class, this::principal);
    }
    @Test void authorizeOnlyRequestedOwnedVideosAndCheckAgainAtQuery() {
        assertEquals(Set.of(1001L), metrics.allowed(principal(), Set.of(1001L,1002L)));
        assertEquals(403, assertThrows(BridgeException.class, () -> metrics.query(principal(), 1002, day, "bullet_count")).status());
        db.update("UPDATE video SET user_id=882 WHERE video_id=1001");
        assertThrows(BridgeException.class, () -> metrics.query(principal(),1001,day,"bullet_count"));
    }
    @Test void halfOpenBusinessDayCountsRowsInsteadOfCumulativeCounter() {
        bullet(1,start().minusMillis(1)); bullet(2,start()); bullet(3,start().plusSeconds(123));
        bullet(4,start().plusSeconds(86400).minusMillis(1)); bullet(5,start().plusSeconds(86400));
        db.update("INSERT INTO video_stats(video_id,bullet_count) VALUES(1001,999)");
        var result = metrics.query(principal(),1001,day,"bullet_count");
        assertEquals(3,result.value()); assertFalse(result.demo()); assertTrue(result.complete());
        assertEquals("mysql:bullet:retained-persisted-v1",result.source());
        assertEquals(0,metrics.query(principal(),1003,day,"bullet_count").value());
    }
    @Test void deletingRowsChangesRetainedMetricAndDatesAreBounded() {
        bullet(1,start()); bullet(2,start().plusSeconds(1)); db.update("DELETE FROM bullet WHERE bullet_id=1");
        assertEquals(1,metrics.query(principal(),1001,day,"bullet_count").value());
        assertThrows(BridgeException.class, () -> metrics.query(principal(),1001,day.plusDays(1),"bullet_count"));
        assertThrows(BridgeException.class, () -> metrics.query(principal(),1001,day.minusDays(31),"bullet_count"));
    }
    @Test void datetimeColumnUsesConfiguredStorageWallClockZone() {
        db.execute("ALTER TABLE bullet MODIFY create_time DATETIME(3) NOT NULL");
        try {
            var service = new BulletIngestionService(db);
            transaction.executeWithoutResult(s -> service.persist(event(9001)));
            db.update("INSERT INTO bullet(bullet_id,video_id,user_id,content,playback_time,create_time) VALUES(2,1001,881,'fixture',1,?)",day.plusDays(1)+" 00:00:00");
            assertEquals(1,metrics.query(principal(),1001,day,"bullet_count").value());
        } finally {
            db.update("DELETE FROM bullet WHERE video_id=1001");
            db.execute("ALTER TABLE bullet MODIFY create_time TIMESTAMP(3) NOT NULL");
        }
    }
    @Test void privateReadPoolRejectsWritesAndDoesNotChangeWriterTimeZone() throws Exception {
        try (var writer = source.getConnection()) {
            writer.createStatement().execute("SET time_zone='+08:00'");
            try (var reader = new BusinessReadDatabase(source,"","","")) {
                JdbcTemplate read = new JdbcTemplate(reader.source());
                assertEquals("+00:00",read.queryForObject("SELECT @@session.time_zone",String.class));
                assertThrows(Exception.class,()->read.update("UPDATE video SET user_id=882 WHERE video_id=1001"));
                try (var result = writer.createStatement().executeQuery("SELECT @@session.time_zone")) {
                    assertTrue(result.next()); assertEquals("+08:00",result.getString(1));
                }
            } finally { writer.createStatement().execute("SET time_zone='+00:00'"); }
        }
    }
    SendBulletRequest event(long id) {
        var event = new SendBulletRequest(); event.setBulletId(id); event.setVideoId(1001L); event.setUserId(881L);
        event.setContent("idempotent"); event.setColor("ffffff"); event.setPlaybackTime(1d); event.setCreateTime(Date.from(start())); return event;
    }
    @Test void concurrentDuplicateEventCreatesOneRowAndOneCounterIncrement() throws Exception {
        var service = new BulletIngestionService(db); var pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i=0;i<8;i++) calls.add(pool.submit(() -> transaction.executeWithoutResult(s -> service.persist(event(9001)))));
            for (var call : calls) call.get(10,TimeUnit.SECONDS);
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM bullet WHERE bullet_id=9001",Long.class));
            assertEquals(1,db.queryForObject("SELECT bullet_count FROM video_stats WHERE video_id=1001",Long.class));
            assertEquals(1,metrics.query(principal(),1001,day,"bullet_count").value());
        } finally { pool.shutdownNow(); }
    }
    @Test void eventIdPayloadConflictCannotIncrementCounter() {
        var service = new BulletIngestionService(db); transaction.executeWithoutResult(s -> service.persist(event(9001)));
        var changed = event(9001); changed.setContent("tampered");
        assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(s -> service.persist(changed)));
        assertEquals(1,db.queryForObject("SELECT bullet_count FROM video_stats WHERE video_id=1001",Long.class));
    }
    @Test void counterFailureRollsBackBulletInsertion() {
        String trigger="business_it_counter_fault";
        db.execute("DROP TRIGGER IF EXISTS " + trigger);
        db.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE ON video_stats FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test counter fault'");
        try {
            var service = new BulletIngestionService(db);
            assertThrows(Exception.class, () -> transaction.executeWithoutResult(s -> service.persist(event(9001))));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM bullet WHERE bullet_id=9001",Long.class));
        } finally { db.execute("DROP TRIGGER " + trigger); }
    }
}
