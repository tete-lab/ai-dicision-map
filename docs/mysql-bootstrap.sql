-- AI Decision Map / MySQL 8 bootstrap
-- Run this once with a MySQL administrator account on 218.156.22.171:9001.
-- IMPORTANT: replace CHANGE_ME_WITH_A_STRONG_RANDOM_PASSWORD before executing.

CREATE DATABASE IF NOT EXISTS ai_decision_map
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'decision_map_app'@'%'
    IDENTIFIED BY 'CHANGE_ME_WITH_A_STRONG_RANDOM_PASSWORD';

-- CREATE USER IF NOT EXISTS does not update an existing password, so this line
-- makes the script repeatable after you replace the placeholder.
ALTER USER 'decision_map_app'@'%'
    IDENTIFIED BY 'CHANGE_ME_WITH_A_STRONG_RANDOM_PASSWORD';

-- Flyway needs schema migration privileges during the MVP phase.
-- There is deliberately no GRANT OPTION and no access outside this database.
GRANT SELECT, INSERT, UPDATE, DELETE,
      CREATE, ALTER, INDEX, DROP, REFERENCES,
      CREATE TEMPORARY TABLES, LOCK TABLES
ON ai_decision_map.*
TO 'decision_map_app'@'%';

FLUSH PRIVILEGES;

-- Verification
SHOW GRANTS FOR 'decision_map_app'@'%';
SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'ai_decision_map';

-- Security hardening after the deployment server IP is fixed:
-- 1. Create the same account restricted to the application server IP.
-- 2. Grant the same database privileges to that account.
-- 3. Drop the '%' account after verifying the application connection.
