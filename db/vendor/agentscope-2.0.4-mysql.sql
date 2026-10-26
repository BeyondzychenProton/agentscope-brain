-- AgentScope JDBC 2.0.4 MysqlDialect, default agentscope_ prefix.
-- Source: io/agentscope/extensions/jdbc/dialect/vendor/MysqlDialect.java in the pinned sources JAR.
-- Kept separate from platform SQL; execute only through an explicitly approved structural change.
CREATE TABLE agentscope_store (
  namespace_path VARCHAR(512) NOT NULL,
  item_key VARCHAR(255) NOT NULL,
  value_json LONGTEXT NOT NULL,
  version BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  PRIMARY KEY (namespace_path, item_key),
  INDEX idx_namespace (namespace_path)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE agentscope_sessions (
  session_id VARCHAR(255) NOT NULL,
  state_key VARCHAR(255) NOT NULL,
  item_index INT NOT NULL DEFAULT 0,
  state_data LONGTEXT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (session_id, state_key, item_index),
  INDEX idx_session (session_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE TABLE agentscope_snapshots (
  snapshot_id VARCHAR(512) NOT NULL PRIMARY KEY,
  data LONGBLOB NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
