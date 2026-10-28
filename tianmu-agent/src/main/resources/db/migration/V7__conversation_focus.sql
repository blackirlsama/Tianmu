CREATE TABLE agent_conversation_focus (
    tenant_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    turn_seq BIGINT NOT NULL,
    focus_json MEDIUMTEXT NOT NULL,
    PRIMARY KEY (tenant_id, owner_user_id, session_id),
    CONSTRAINT fk_focus_session FOREIGN KEY (tenant_id, owner_user_id, session_id)
        REFERENCES agent_session(tenant_id, owner_user_id, session_id) ON DELETE CASCADE
);
