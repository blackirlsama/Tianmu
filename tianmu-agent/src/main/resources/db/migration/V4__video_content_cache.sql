CREATE TABLE agent_video_content_cache (
    cache_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    facts_json JSON NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    INDEX idx_video_content_expiry (expires_at)
);
