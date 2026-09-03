CREATE TABLE decision_options (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    option_key VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    summary TEXT NULL,
    score DECIMAL(5,2) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_decision_options_session_key UNIQUE (session_id, option_key),
    CONSTRAINT fk_decision_options_session
        FOREIGN KEY (session_id) REFERENCES decision_sessions (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_decision_options_score CHECK (score IS NULL OR score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE decision_criteria (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    criterion_key VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    weight DECIMAL(8,7) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_decision_criteria_session_key UNIQUE (session_id, criterion_key),
    CONSTRAINT fk_decision_criteria_session
        FOREIGN KEY (session_id) REFERENCES decision_sessions (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_decision_criteria_weight CHECK (weight BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE criterion_option_scores (
    id BIGINT NOT NULL AUTO_INCREMENT,
    criterion_id BIGINT NOT NULL,
    option_id BIGINT NOT NULL,
    score DECIMAL(5,2) NOT NULL,
    reason TEXT NULL,
    source_type VARCHAR(30) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_criterion_option_scores_pair UNIQUE (criterion_id, option_id),
    CONSTRAINT fk_criterion_option_scores_criterion
        FOREIGN KEY (criterion_id) REFERENCES decision_criteria (id)
        ON DELETE CASCADE,
    CONSTRAINT fk_criterion_option_scores_option
        FOREIGN KEY (option_id) REFERENCES decision_options (id)
        ON DELETE CASCADE,
    CONSTRAINT chk_criterion_option_scores_score CHECK (score BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE decision_insights (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    insight_type VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    priority INT NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_decision_insights_session_priority (session_id, priority),
    CONSTRAINT fk_decision_insights_session
        FOREIGN KEY (session_id) REFERENCES decision_sessions (id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
