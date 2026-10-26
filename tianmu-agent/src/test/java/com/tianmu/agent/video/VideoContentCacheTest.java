package com.tianmu.agent.video;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VideoContentCacheTest {
    @Test void slowDatabaseReadDoesNotSerializeOtherKeys() throws Exception {
        var db = mock(JdbcTemplate.class); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(db.queryForList(anyString(), eq(String.class), eq("slow"))).thenAnswer(call -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return List.of();
        });
        when(db.queryForList(anyString(), eq(String.class), eq("fast"))).thenReturn(List.of());
        var cache = new VideoContentCache(db, new ObjectMapper()); var pool = Executors.newFixedThreadPool(2);
        try {
            var slow = pool.submit(() -> cache.get("slow")); assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNull(pool.submit(() -> cache.get("fast")).get(2, TimeUnit.SECONDS));
            assertFalse(slow.isDone()); release.countDown(); assertNull(slow.get(2, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void corruptReadOnlyDeletesTheExactPayloadItRead() {
        var db = mock(JdbcTemplate.class);
        when(db.queryForList(anyString(), eq(String.class), eq("key"))).thenReturn(List.of("{}"));
        assertNull(new VideoContentCache(db, new ObjectMapper()).get("key"));
        verify(db).update("DELETE FROM agent_video_content_cache WHERE cache_key=? AND facts_json=CAST(? AS JSON)", "key", "{}");
    }
}
