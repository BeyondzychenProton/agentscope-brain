-- Issue #46 的 CopilotKit 线程与业务会话分开保存。此脚本不会在应用启动时自动执行。
CREATE TABLE copilot_thread (
    thread_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'CopilotKit 线程 UUID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属平台用户',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '所属数字员工',
    employee_release_id BIGINT UNSIGNED NOT NULL COMMENT '创建时固定的员工发布版本',
    title VARCHAR(200) NOT NULL COMMENT '用户可修改的对话标题',
    active_run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '当前活动执行 UUID',
    last_message_no BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '线程内最后消息序号',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '标题与状态并发修订',
    last_activity_at DATETIME(6) NOT NULL COMMENT '最近活动时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '修改时间，UTC',
    PRIMARY KEY (thread_id),
    KEY idx_copilot_thread_owner (user_id, employee_id, last_activity_at, thread_id),
    CONSTRAINT chk_copilot_thread_identity CHECK (user_id > 0 AND employee_id > 0 AND employee_release_id > 0),
    CONSTRAINT chk_copilot_thread_title CHECK (CHAR_LENGTH(TRIM(title)) > 0),
    CONSTRAINT chk_copilot_thread_version CHECK (row_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台所属 CopilotKit 线程，不是业务会话';

CREATE TABLE copilot_thread_message (
    thread_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属线程 UUID',
    message_no BIGINT UNSIGNED NOT NULL COMMENT '线程内单调消息序号',
    message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '消息 UUID',
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属文本执行 UUID',
    role VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'USER 或 ASSISTANT',
    content LONGTEXT NOT NULL COMMENT '消息正文或未完成片段',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'COMPLETE、STREAMING 或 UNFINISHED',
    gmt_create DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '修改时间，UTC',
    PRIMARY KEY (thread_id, message_no),
    UNIQUE KEY uk_copilot_message_id (thread_id, message_id),
    KEY idx_copilot_message_run (thread_id, run_id),
    CONSTRAINT chk_copilot_message_no CHECK (message_no > 0),
    CONSTRAINT chk_copilot_message_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_copilot_message_status CHECK (status IN ('COMPLETE', 'STREAMING', 'UNFINISHED')),
    CONSTRAINT chk_copilot_user_complete CHECK (role <> 'USER' OR status = 'COMPLETE')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='完成消息与未完成片段，不写入正式消息';

CREATE TABLE copilot_thread_run (
    thread_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '所属线程 UUID',
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '文本执行 UUID',
    session_id_hash BINARY(32) NOT NULL COMMENT '发起该次运行的平台会话摘要，不存 Cookie',
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '执行运行或终态',
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '无秘密的受控失败原因',
    started_at DATETIME(6) NOT NULL COMMENT '执行接纳时间，UTC',
    lease_until DATETIME(6) NOT NULL COMMENT '执行进程续约；过期后允许标记中断',
    ended_at DATETIME(6) NULL COMMENT '终态时间，UTC',
    PRIMARY KEY (thread_id, run_id),
    KEY idx_copilot_run_session (session_id_hash, state),
    CONSTRAINT chk_copilot_run_state CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED')),
    CONSTRAINT chk_copilot_run_end CHECK ((state = 'RUNNING' AND ended_at IS NULL) OR (state <> 'RUNNING' AND ended_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文本流执行状态，不是业务 Run';
