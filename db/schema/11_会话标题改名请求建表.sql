-- 会话标题改名幂等请求（# 切片：会话改名）。依赖 02 会话表已存在。
-- 同 user_id + request_key 只生效一次；重放返回原结果，不覆盖较新标题。

CREATE TABLE session_title_request (
                                       user_id BIGINT UNSIGNED NOT NULL COMMENT '稳定 UserId',
                                       request_key BINARY(16) NOT NULL COMMENT '客户端改名幂等键 UUID',
                                       request_sha256 BINARY(32) NOT NULL COMMENT 'sessionId|expectedVersion|title 的 SHA-256',
                                       session_id BIGINT UNSIGNED NOT NULL COMMENT '目标业务 SessionId',
                                       title VARCHAR(200) NOT NULL COMMENT '该请求生效的标题',
                                       result_session_version BIGINT UNSIGNED NOT NULL COMMENT '生效后的 session row_version',
                                       gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
                                       PRIMARY KEY (user_id, request_key),
                                       KEY idx_session_title_request_session (session_id),
                                       CONSTRAINT chk_session_title_request_version CHECK (result_session_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话标题改名幂等请求';
