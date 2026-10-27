CREATE TABLE agent_report_cache (
    cache_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    progress_rank BIGINT NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    INDEX idx_report_cache_expiry(expires_at)
);

CREATE TABLE agent_qa_catalog (
    owner_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    question_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state_json JSON NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY(owner_key,question_key),
    INDEX idx_qa_owner_updated(owner_key,updated_at)
);

CREATE TABLE agent_report_leases (
    cache_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    valid_until DATETIME(6) NOT NULL,
    INDEX idx_report_lease_run(run_id)
);
