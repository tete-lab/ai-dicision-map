CREATE TABLE decision_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    public_id VARCHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    stage VARCHAR(50) NOT NULL,
    progress INT NOT NULL DEFAULT 0,
    summary TEXT NULL,
    state_json LONGTEXT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_decision_sessions_public_id UNIQUE (public_id),
    CONSTRAINT chk_decision_sessions_progress CHECK (progress BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_messages_session_id_id (session_id, id),
    CONSTRAINT fk_messages_decision_session
        FOREIGN KEY (session_id) REFERENCES decision_sessions (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
