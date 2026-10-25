package com.tianmu.agent.video;

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
    private record Entry(VideoContent.Facts facts, Instant expires) {}
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Map<String,Entry> memory = new LinkedHashMap<>();
    public VideoContentCache(ObjectProvider<DataSource> source, ObjectMapper json) {
        DataSource available = source.getIfAvailable(); this.db = available == null ? null : new JdbcTemplate(available); this.json = json;
    }
    public synchronized VideoContent.Facts get(String key) {
        if (db == null) {
            Entry value = memory.get(key);
            if (value != null && value.expires().isAfter(Instant.now())) return value.facts();
            memory.remove(key); return null;
        }
        var rows = db.queryForList("SELECT facts_json FROM agent_video_content_cache WHERE cache_key=? AND expires_at>UTC_TIMESTAMP(6)", String.class, key);
        if (rows.isEmpty()) return null;
        try { return json.readValue(rows.get(0), VideoContent.Facts.class); }
        catch (Exception e) { db.update("DELETE FROM agent_video_content_cache WHERE cache_key=?",key); return null; }
    }
    public synchronized void put(String key, VideoContent.Facts facts) {
        if (db == null) {
            memory.entrySet().removeIf(e -> e.getValue().expires().isBefore(Instant.now()));
            if (memory.size() >= 128) memory.remove(memory.keySet().iterator().next());
            memory.put(key,new Entry(facts,Instant.now().plusSeconds(3600))); return;
        }
        try {
            String body = json.writeValueAsString(facts);
            db.update("INSERT INTO agent_video_content_cache(cache_key,facts_json,expires_at) VALUES(?,?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY)) ON DUPLICATE KEY UPDATE facts_json=VALUES(facts_json),expires_at=VALUES(expires_at)",key,body);
            db.update("DELETE FROM agent_video_content_cache WHERE expires_at<=UTC_TIMESTAMP(6) LIMIT 100");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("Invalid video cache payload"); }
    }
}
