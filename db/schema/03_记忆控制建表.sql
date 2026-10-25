-- 一期长期记忆平台控制记录（Issue #9 评审稿）；适用于 MySQL 8.0.16+。
-- 先在同一专用空 schema 中依次执行 01_用户与管理员建表.sql、02_会话运行消息建表.sql。
-- 本文件只新增两张控制表：记忆正文和日记只存于框架 JDBC KV（默认表 agentscope_store，由框架依锁定版本初始化），
-- 本文件不创建该表，也不保存任何正文、候选正文或 JSON 副本。
-- 本文件不修改既有表；business_message、business_run、conversation_audit_event 需要的记忆列已并入 02_会话运行消息建表.sql。
-- 约定同前：每表 id/gmt_create/gmt_modified、状态码使用 ascii_bin、无物理外键与存储过程、业务时间由应用按 UTC 写入。
-- 这是待评审建表方案；DDL 不能证明一致激活协议、任务恢复或框架 KV 往返已经实现。

-- 每个（用户，员工）一行：当前有效代次、文档修订、正文核对摘要、输入序号和手工操作去重。
-- 手工保存/清空/自动合并都以 WHERE generation = ? AND document_revision = ? 条件更新，旧值更新 0 行即冲突。
CREATE TABLE memory_profile (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记忆控制记录主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '记忆所有者稳定 UserId，逻辑引用 platform_user.id',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '数字员工 ID，与 business_session.employee_id 同源，逻辑引用 digital_employee.id',
    profile_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY' COMMENT 'READY 可用、CORRUPTED 正文缺失或摘要不符、RECONCILING 提交结果待核对',
    generation BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前有效记忆代次；每次手工保存或清空加一，不复用',
    document_revision BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前代次内 MEMORY.md 的修订；仅正文变化时推进，日记追加不推进',
    document_kv_version BIGINT UNSIGNED NOT NULL COMMENT '最近一次激活或合并时观察到的框架 KV 记录版本，用于与 document_revision 交叉核对',
    document_sha256 BINARY(32) NOT NULL COMMENT '有效 MEMORY.md 内容的 SHA-256；空文档为空字节串的摘要，不得以缺失表示清空',
    memory_schema_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '记忆文档格式版本，不随员工发布自动变化',
    policy_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '提取与合并策略版本',
    generation_input_cutoff BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前代次建立时的最后输入序号；只有序号更大的用户输入可成为本代次学习来源',
    last_input_sequence BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '该用户该员工范围内已分配的最后用户输入接纳序号',
    prepared_operation_key BINARY(16) NULL COMMENT '正在准备下一代次的手工操作 UUID；无进行中准备时为空',
    prepared_expires_at DATETIME(6) NULL COMMENT '准备预留的过期时间，UTC；过期后其他操作可重新预留',
    last_operation_key BINARY(16) NULL COMMENT '最近一次已激活手工操作的 UUID，用于重试去重',
    last_operation_sha256 BINARY(32) NULL COMMENT '最近一次已激活手工操作的稳定语义摘要，同键不同内容须拒绝',
    activated_at DATETIME(6) NOT NULL COMMENT '当前代次的激活时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_employee (user_id, employee_id),
    CONSTRAINT chk_memory_profile_status CHECK (profile_status IN ('READY', 'CORRUPTED', 'RECONCILING')),
    CONSTRAINT chk_memory_profile_generation CHECK (generation > 0),
    CONSTRAINT chk_memory_profile_revision CHECK (document_revision > 0),
    CONSTRAINT chk_memory_profile_cutoff CHECK (generation_input_cutoff <= last_input_sequence),
    CONSTRAINT chk_memory_profile_prepare CHECK ((prepared_operation_key IS NULL AND prepared_expires_at IS NULL) OR (prepared_operation_key IS NOT NULL AND prepared_expires_at IS NOT NULL)),
    CONSTRAINT chk_memory_profile_last_operation CHECK ((last_operation_key IS NULL AND last_operation_sha256 IS NULL) OR (last_operation_key IS NOT NULL AND last_operation_sha256 IS NOT NULL)),
    CONSTRAINT chk_memory_profile_versions CHECK (CHAR_LENGTH(TRIM(memory_schema_version)) > 0 AND CHAR_LENGTH(TRIM(policy_version)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户与员工范围的长期记忆有效代次、修订和输入顺序控制记录';

-- 一次成功 Run 产生的记忆提取（FLUSH）或合并（CONSOLIDATE）任务。任务存在不代表内容已提交。
-- 新代次激活事务内把旧代次 PENDING/RUNNING 任务批量置 STALE；领取和提交时仍须复核代次、修订与授权版本。
CREATE TABLE memory_update_job (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记忆更新任务主键，同时作为日记追加和结果应用的去重标识',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '记忆所有者 UserId',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '数字员工 ID',
    generation BIGINT UNSIGNED NOT NULL COMMENT '来源 Run 启动时捕获的记忆代次；不随当前代次移动',
    task_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'FLUSH 提取到当前代次日记，CONSOLIDATE 合并到 MEMORY.md',
    source_session_id BIGINT UNSIGNED NOT NULL COMMENT '来源业务 SessionId',
    source_run_id BIGINT UNSIGNED NOT NULL COMMENT '已成功的来源 RunId；CONSOLIDATE 任务锚定到触发它的成功 Run',
    source_message_id BIGINT UNSIGNED NOT NULL COMMENT '来源 Run 的正式用户输入消息 ID，逻辑引用 business_message.id',
    source_input_sequence BIGINT UNSIGNED NOT NULL COMMENT '来源用户输入的接纳序号，用于偏好冲突判定；必须大于任务代次的 generation_input_cutoff',
    authorization_version_at_creation BIGINT UNSIGNED NOT NULL COMMENT '任务创建时 platform_user.authorization_version 的值；领取和提交时不相等则取消',
    memory_schema_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建任务时的记忆文档格式版本',
    policy_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '创建任务时的提取与合并策略版本',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、RUNNING、SUCCEEDED、FAILED、STALE 或 CANCELLED',
    baseline_document_revision BIGINT UNSIGNED NULL COMMENT '领取时读取的 MEMORY.md 修订；提交时与当前修订不符则重新读取并重新核对',
    attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已领取执行次数；瞬时错误回到 PENDING，达到应用配置的上限转 FAILED',
    next_attempt_at DATETIME(6) NOT NULL COMMENT '最早可领取时间，UTC',
    claim_token BINARY(16) NULL COMMENT '当前或最近一次领取的随机凭据；提交时必须一致',
    claimed_at DATETIME(6) NULL COMMENT '最近一次领取时间，UTC',
    lease_expires_at DATETIME(6) NULL COMMENT '领取租约过期时间，UTC；过期后须先核对是否已应用再重新领取',
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '最近一次瞬时失败，或 FAILED、STALE、CANCELLED 的受控原因码',
    candidate_locator VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '候选结果在框架 KV 中的定位，仅供核对；不是权威正文',
    candidate_sha256 BINARY(32) NULL COMMENT '候选结果内容的 SHA-256',
    applied_document_revision BIGINT UNSIGNED NULL COMMENT 'CONSOLIDATE 应用后的 MEMORY.md 修订；FLUSH 不推进修订，保持为空',
    applied_at DATETIME(6) NULL COMMENT '结果已应用到框架 KV 的时间，UTC',
    finished_at DATETIME(6) NULL COMMENT '进入终态的时间，UTC；PENDING 与 RUNNING 为空',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_run_generation_task_type (source_run_id, generation, task_type(16)),
    KEY idx_status_next_attempt_at (status(16), next_attempt_at),
    KEY idx_status_lease_expires_at (status(16), lease_expires_at),
    KEY idx_user_employee_generation_status (user_id, employee_id, generation, status(16)),
    CONSTRAINT chk_memory_job_task_type CHECK (task_type IN ('FLUSH', 'CONSOLIDATE')),
    CONSTRAINT chk_memory_job_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'STALE', 'CANCELLED')),
    CONSTRAINT chk_memory_job_generation CHECK (generation > 0),
    CONSTRAINT chk_memory_job_sequence CHECK (source_input_sequence > 0),
    CONSTRAINT chk_memory_job_authorization CHECK (authorization_version_at_creation > 0),
    CONSTRAINT chk_memory_job_versions CHECK (CHAR_LENGTH(TRIM(memory_schema_version)) > 0 AND CHAR_LENGTH(TRIM(policy_version)) > 0),
    CONSTRAINT chk_memory_job_running CHECK (status <> 'RUNNING' OR (claim_token IS NOT NULL AND claimed_at IS NOT NULL AND lease_expires_at IS NOT NULL)),
    CONSTRAINT chk_memory_job_terminal CHECK ((status NOT IN ('SUCCEEDED', 'FAILED', 'STALE', 'CANCELLED') OR finished_at IS NOT NULL) AND (status NOT IN ('PENDING', 'RUNNING') OR finished_at IS NULL)),
    CONSTRAINT chk_memory_job_reason CHECK (status NOT IN ('FAILED', 'STALE', 'CANCELLED') OR reason_code IS NOT NULL),
    CONSTRAINT chk_memory_job_success CHECK (status <> 'SUCCEEDED' OR (applied_at IS NOT NULL AND (task_type = 'FLUSH' OR applied_document_revision IS NOT NULL))),
    CONSTRAINT chk_memory_job_candidate CHECK ((candidate_locator IS NULL AND candidate_sha256 IS NULL) OR (candidate_locator IS NOT NULL AND candidate_sha256 IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='成功 Run 触发的长期记忆提取与合并任务';
