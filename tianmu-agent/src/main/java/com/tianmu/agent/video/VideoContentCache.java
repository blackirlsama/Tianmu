package com.tianmu.agent.video;

import com.tianmu.agent.core.VideoContent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;

/** Cache keys include tenant, actor, source version, range, focus and model configuration. Authorization precedes every read. */
@Component
public final class VideoContentCache {

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final com.github.benmanes.caffeine.cache.Cache<String, VideoContent.Facts> memory = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
            .maximumSize(128).expireAfterWrite(java.time.Duration.ofHours(1)).build();
    @org.springframework.beans.factory.annotation.Autowired
    public VideoContentCache(ObjectProvider<DataSource> source, ObjectMapper json) {
        DataSource available = source.getIfAvailable(); this.db = available == null ? null : new JdbcTemplate(available); this.json = json;
    }
    VideoContentCache(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }
    public VideoContent.Facts get(String key) {
        if (db == null) return memory.getIfPresent(key);
        var rows = db.queryForList("SELECT facts_json FROM agent_video_content_cache WHERE cache_key=? AND expires_at>UTC_TIMESTAMP(6)", String.class, key);
        if (rows.isEmpty()) return null;
        try { return json.readValue(rows.get(0), VideoContent.Facts.class); }
        catch (Exception e) { db.update("DELETE FROM agent_video_content_cache WHERE cache_key=? AND facts_json=CAST(? AS JSON)",key,rows.get(0)); return null; }
    }
    public void put(String key, VideoContent.Facts facts) {
        if (db == null) { memory.put(key, facts); return; }
        try {
            String body = json.writeValueAsString(facts);
            db.update("INSERT INTO agent_video_content_cache(cache_key,facts_json,expires_at) VALUES(?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY)) ON DUPLICATE KEY UPDATE facts_json=VALUES(facts_json),expires_at=VALUES(expires_at)",key,body);
            db.update("DELETE FROM agent_video_content_cache WHERE expires_at<=UTC_TIMESTAMP(6) LIMIT 100");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Invalid video cache payload"); }
    }
}
