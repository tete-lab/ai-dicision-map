ALTER TABLE decision_sessions
    ADD COLUMN result_narrative_json LONGTEXT NULL AFTER state_json;
