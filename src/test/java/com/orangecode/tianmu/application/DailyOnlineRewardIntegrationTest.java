package com.orangecode.tianmu.application;

import com.zaxxer.hikari.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="ONLINE_REWARD_IT", matches="true")
class DailyOnlineRewardIntegrationTest {
    private HikariDataSource source;
    private JdbcTemplate db;
    private static final long USER = 8100991L;

    @BeforeEach void setup() {
        String url = System.getenv("BUSINESS_JDBC_URL");
        assertNotNull(url);
        assertTrue(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/[A-Za-z0-9_]+_it(?:\\?.*)?"), "Dedicated local _it database required");
        var config = new HikariConfig(); config.setJdbcUrl(url);
        config.setUsername(System.getenv("BUSINESS_DB_USER")); config.setPassword(System.getenv("BUSINESS_DB_PASSWORD"));
        config.setMaximumPoolSize(8); source = new HikariDataSource(config); db = new JdbcTemplate(source);
        new ResourceDatabasePopulator(new FileSystemResource("deploy/site/business-schema.sql")).execute(source);
        db.update("DELETE FROM user_stats WHERE user_id=?", USER);
        db.update("INSERT INTO user_stats(user_id,coin_count) VALUES(?,0)", USER);
    }
    @AfterEach void close() { if (source != null) source.close(); }

    @Test void repeatedVisitsAndSpentCoinsCannotClaimAgainOnTheSameDay() {
        var rewards = service(db, "2026-10-31T15:59:59Z");
        assertTrue(rewards.visit(USER).rewarded());
        assertEquals(1, rewards.visit(USER).coinCount()); assertFalse(rewards.visit(USER).rewarded());
        db.update("UPDATE user_stats SET coin_count=0 WHERE user_id=?", USER);
        assertEquals(0, rewards.visit(USER).coinCount()); assertFalse(rewards.visit(USER).rewarded());
    }

    @Test void concurrentTabsAndInstancesReceiveExactlyOneCoin() throws Exception {
        var first = service(db, "2026-10-31T12:00:00Z"); var second = service(db, "2026-10-31T12:00:00Z");
        var pool = Executors.newFixedThreadPool(8); var start = new CountDownLatch(1);
        try {
            List<Future<DailyOnlineReward.Result>> calls = new ArrayList<>();
            for (int i=0; i<16; i++) {
                var instance = i % 2 == 0 ? first : second;
                calls.add(pool.submit(() -> { start.await(); return instance.visit(USER); }));
            }
            start.countDown(); int grants = 0;
            for (var call : calls) if (call.get(20, TimeUnit.SECONDS).rewarded()) grants++;
            assertEquals(1, grants); assertEquals(1, balance());
            assertEquals("2026-10-31", db.queryForObject("SELECT last_online_reward_date FROM user_stats WHERE user_id=?", String.class, USER));
        } finally { pool.shutdownNow(); }
    }

    @Test void beijingMidnightGrantsNextDaysCoinWithoutAnotherLogin() {
        assertEquals(1, service(db, "2026-10-31T15:59:59Z").visit(USER).coinCount());
        var next = service(db, "2026-10-31T16:00:00Z").visit(USER);
        assertTrue(next.rewarded()); assertEquals(2, next.coinCount()); assertEquals(LocalDate.of(2026,11,1), next.day());
        assertFalse(service(db, "2026-10-31T17:00:00Z").visit(USER).rewarded());
        assertFalse(service(db, "2026-10-31T15:00:00Z").visit(USER).rewarded()); // clock rollback
    }

    @Test void failureAfterAwardUpdateRollsBackBothBalanceAndRewardDate() {
        var faulty = spy(db);
        doThrow(new IllegalStateException("test read failure")).when(faulty).queryForList(anyString(), eq(Integer.class), eq(USER));
        assertThrows(IllegalStateException.class, () -> service(faulty, "2026-10-31T12:00:00Z").visit(USER));
        assertEquals(0, balance());
        assertNull(db.queryForObject("SELECT last_online_reward_date FROM user_stats WHERE user_id=?", java.sql.Date.class, USER));
        assertTrue(service(db, "2026-10-31T12:00:00Z").visit(USER).rewarded());
        assertEquals(1, balance());
    }

    private int balance() { return db.queryForObject("SELECT coin_count FROM user_stats WHERE user_id=?", Integer.class, USER); }
    private DailyOnlineReward service(JdbcTemplate jdbc, String at) {
        var target = new DailyOnlineReward(jdbc, Clock.fixed(Instant.parse(at), ZoneOffset.UTC));
        var transaction = new TransactionInterceptor();
        transaction.setTransactionManager(new DataSourceTransactionManager(source));
        transaction.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var proxy = new ProxyFactory(target); proxy.addAdvice(transaction); return (DailyOnlineReward) proxy.getProxy();
    }
}
