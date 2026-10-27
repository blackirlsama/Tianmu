package com.tianmu.agent.adapters.storage;

import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisCacheStoreTest {
    @Test void unavailablePersistenceIsAMissAndCooldownPreventsRepeatedDatabaseAttempts() throws Exception {
        DataSource source=mock(DataSource.class);var attempts=new AtomicInteger();
        when(source.getConnection()).thenAnswer(invocation->{attempts.incrementAndGet();throw new SQLException("offline");});
        var store=new MysqlAnalysisCacheStore(source);String key="a".repeat(64);
        assertNull(store.get(key));int after=attempts.get();assertTrue(after>0);
        assertDoesNotThrow(()->store.put(key,new com.tianmu.agent.runtime.store.AnalysisCacheStore.Entry(1,"{}",Instant.now().plusSeconds(60))));
        assertNull(store.get(key));assertEquals(after,attempts.get());
    }
}
