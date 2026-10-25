-- 一期 Web 会话、Run、正式消息、通用素材、版本续接、工具操作确认与审计的表结构设计。
-- 2026-10-19 调整：素材原件使用私有对象存储，SQL 保存归属和稳定定位；操作与确认不按业务建表。
-- 目标：MySQL 8.0.16+；先在专用空 schema 中执行 01_用户与管理员建表.sql。
-- 本文件只定义平台业务表，不创建 AgentScope 框架状态表、数字员工定义表或未来渠道表。
-- 所有业务时间由应用按 UTC 写入；无物理外键、级联、存储过程或默认测试数据。
-- 这是待评审的建表方案，不能单凭 DDL 宣称业务流程和 AgentScope 状态恢复已实现。
-- Issue #9 已把长期记忆需要的列直接并入 business_message、business_run、conversation_audit_event（空库建表，不含已有数据迁移）：
-- 已有数据的库需自行回填 business_run 的三个记忆快照列，以及 USER 消息的 employee_id 与 input_sequence。

CREATE TABLE business_session (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '平台业务 SessionId',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '稳定 UserId，逻辑引用 platform_user.id',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '数字员工 ID，逻辑引用 digital_employee.id；完整建库须执行 05 号 DDL',
    employee_release_id BIGINT UNSIGNED NOT NULL COMMENT '创建时固定的发布版本 ID，逻辑引用 employee_release.id',
    entry_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'WEB' COMMENT '会话入口；一期仅 WEB',
    source_session_id BIGINT UNSIGNED NULL COMMENT '版本续接的直接来源 SessionId；普通会话为空',
    lifecycle_status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE、PENDING_ACTIVATION、READ_ONLY 或 ABANDONED',
    successful_source_session_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN source_session_id IS NOT NULL AND lifecycle_status IN ('ACTIVE', 'READ_ONLY')
              THEN source_session_id ELSE NULL END) STORED COMMENT '成功后继的来源唯一键',
    is_archived TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否归档：1 是，0 否；不改变可运行性',
    recovery_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'READY' COMMENT 'READY、STATE_MISSING 或 RECONCILING',
    state_session_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的不复用小写十六进制状态槽；作为 AgentScope 逻辑 sessionId 传入',
    last_message_no BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '会话内最后一条正式消息序号',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '会话行并发控制版本',
    last_activity_at DATETIME(6) NOT NULL COMMENT '最近业务活动时间，UTC',
    title VARCHAR(200) NULL COMMENT '会话展示标题；不作为身份或路由键',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_state_session_key (state_session_key(128)),
    UNIQUE KEY uk_successful_source_session_id (successful_source_session_id),
    KEY idx_user_employee_archive_activity (user_id, employee_id, is_archived, last_activity_at),
    KEY idx_source_session_id (source_session_id),
    CONSTRAINT chk_business_session_entry CHECK (entry_kind = 'WEB'),
    CONSTRAINT chk_business_session_lifecycle CHECK (lifecycle_status IN ('ACTIVE', 'PENDING_ACTIVATION', 'READ_ONLY', 'ABANDONED')),
    CONSTRAINT chk_business_session_archive CHECK (is_archived IN (0, 1)),
    CONSTRAINT chk_business_session_recovery CHECK (recovery_status IN ('READY', 'STATE_MISSING', 'RECONCILING')),
    CONSTRAINT chk_business_session_state_key CHECK (CHAR_LENGTH(state_session_key) > 0),
    CONSTRAINT chk_business_session_row_version CHECK (row_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台业务会话及固定发布版本';

CREATE TABLE session_upgrade_attempt (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '一次版本续接尝试 ID',
    source_session_id BIGINT UNSIGNED NOT NULL COMMENT '直接来源业务 SessionId',
    target_session_id BIGINT UNSIGNED NULL COMMENT '首条新版输入接纳后创建的候选 SessionId',
    requested_by_user_id BIGINT UNSIGNED NOT NULL COMMENT '发起续接的稳定 UserId',
    target_release_id BIGINT UNSIGNED NOT NULL COMMENT '拟续接到的发布版本 ID',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '续接尝试生命周期状态',
    open_source_session_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN status IN ('READY_FOR_FIRST_MESSAGE', 'FIRST_RUN_ACTIVE', 'FAILED_RETRYABLE', 'RECONCILING')
              THEN source_session_id ELSE NULL END) STORED COMMENT '同一来源进行中尝试的唯一键',
    successful_source_session_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN status = 'SUCCEEDED' THEN source_session_id ELSE NULL END) STORED COMMENT '同一来源成功尝试的唯一键',
    history_message_count BIGINT UNSIGNED NOT NULL COMMENT '预检时完整前序链的正式消息数',
    history_sha256 BINARY(32) NOT NULL COMMENT '有序前序消息及必需素材摘要的 SHA-256',
    source_last_message_no BIGINT UNSIGNED NOT NULL COMMENT '预检时直接来源 Session 的最后消息序号',
    expires_at DATETIME(6) NOT NULL COMMENT '尚无首条新版消息时的空闲过期时间，UTC',
    first_run_id BIGINT UNSIGNED NULL COMMENT '目标会话首条输入创建的第一个 RunId',
    activation_run_id BIGINT UNSIGNED NULL COMMENT '最终成功激活目标会话的 RunId',
    failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '失败或放弃的受控原因码',
    finished_at DATETIME(6) NULL COMMENT '成功、放弃或过期时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_target_session_id (target_session_id),
    UNIQUE KEY uk_open_source_session_id (open_source_session_id),
    UNIQUE KEY uk_successful_source_session_id (successful_source_session_id),
    KEY idx_source_session_id_gmt_create (source_session_id, gmt_create),
    CONSTRAINT chk_upgrade_attempt_status CHECK (status IN ('READY_FOR_FIRST_MESSAGE', 'FIRST_RUN_ACTIVE', 'FAILED_RETRYABLE', 'RECONCILING', 'SUCCEEDED', 'ABANDONED', 'EXPIRED')),
    CONSTRAINT chk_upgrade_attempt_ready CHECK (status <> 'READY_FOR_FIRST_MESSAGE' OR (target_session_id IS NULL AND first_run_id IS NULL)),
    CONSTRAINT chk_upgrade_attempt_target CHECK (status NOT IN ('FIRST_RUN_ACTIVE', 'FAILED_RETRYABLE', 'RECONCILING', 'SUCCEEDED') OR (target_session_id IS NOT NULL AND first_run_id IS NOT NULL)),
    CONSTRAINT chk_upgrade_attempt_success CHECK (status <> 'SUCCEEDED' OR activation_run_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='业务会话跨发布版本续接尝试';

CREATE TABLE business_message (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '正式业务消息 ID',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 SessionId',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '消息所有者 UserId，应与 Session 相同',
    employee_id BIGINT UNSIGNED NULL COMMENT '仅 USER 消息有值：所属 Session 的数字员工 ID，用于输入序号唯一键；ASSISTANT 为空，由写入校验与 Session 一致',
    message_no BIGINT UNSIGNED NOT NULL COMMENT '会话内从 1 开始的正式消息序号',
    input_sequence BIGINT UNSIGNED NULL COMMENT '仅 USER 消息有值：该用户该员工范围内的全局用户输入接纳序号，由 memory_profile.last_input_sequence 行锁分配；ASSISTANT 为空',
    sender_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'USER 或 ASSISTANT',
    submission_key BINARY(16) NULL COMMENT 'Web 用户提交 UUID；员工回复为空',
    submission_sha256 BINARY(32) NULL COMMENT '稳定提交语义的 SHA-256；员工回复为空',
    initial_run_id BIGINT UNSIGNED NULL COMMENT '用户输入首次创建的 RunId；插入 Run 后在同一事务回填',
    produced_by_run_id BIGINT UNSIGNED NULL COMMENT '产出完整员工回复的成功 RunId',
    accepted_at DATETIME(6) NOT NULL COMMENT '正式消息接纳或完成时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_message_no (session_id, message_no),
    UNIQUE KEY uk_user_submission_key (user_id, submission_key),
    UNIQUE KEY uk_user_employee_input_sequence (user_id, employee_id, input_sequence),
    UNIQUE KEY uk_produced_by_run_id (produced_by_run_id),
    KEY idx_initial_run_id (initial_run_id),
    CONSTRAINT chk_business_message_no CHECK (message_no > 0),
    CONSTRAINT chk_business_message_input_order CHECK (employee_id > 0 AND input_sequence > 0),
    CONSTRAINT chk_business_message_sender CHECK (
        (sender_kind = 'USER' AND submission_key IS NOT NULL AND submission_sha256 IS NOT NULL AND produced_by_run_id IS NULL
            AND employee_id IS NOT NULL AND input_sequence IS NOT NULL)
        OR (sender_kind = 'ASSISTANT' AND submission_key IS NULL AND submission_sha256 IS NULL AND initial_run_id IS NULL AND produced_by_run_id IS NOT NULL
            AND employee_id IS NULL AND input_sequence IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可原地编辑的正式用户与员工消息';

CREATE TABLE message_media (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '正式消息素材 ID，覆盖图片、音频、视频和文件',
    message_id BIGINT UNSIGNED NOT NULL COMMENT '所属正式消息 ID',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属稳定 UserId，应与消息和 Session 相同',
    storage_profile VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变的私有对象存储配置版本引用，不存凭据或临时 URL',
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成的不复用 ASCII 对象键，原件不可覆盖',
    object_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '对象存储提供的原件版本标识；未启用版本时为空',
    media_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'IMAGE、AUDIO、VIDEO 或 FILE；未知文件类型归 FILE',
    mime_type VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '经内容验证的素材 MIME 类型；不是客户端自报值',
    byte_count BIGINT UNSIGNED NOT NULL COMMENT '经校验的原件字节数；通用文件允许零字节',
    content_sha256 BINARY(32) NOT NULL COMMENT '原件 SHA-256，不以对象存储 ETag 代替',
    original_filename VARCHAR(255) NULL COMMENT '上传时文件名，仅作展示，不作为存储路径',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_storage_profile_object_key (storage_profile(64), object_key(512)),
    KEY idx_message_id (message_id),
    CONSTRAINT chk_message_media_kind CHECK (media_kind IN ('IMAGE', 'AUDIO', 'VIDEO', 'FILE')),
    CONSTRAINT chk_message_media_nonempty CHECK (media_kind = 'FILE' OR byte_count > 0),
    CONSTRAINT chk_message_media_locator CHECK (CHAR_LENGTH(TRIM(storage_profile)) > 0 AND CHAR_LENGTH(TRIM(object_key)) > 0),
    CONSTRAINT chk_message_media_mime CHECK (mime_type LIKE '%/%')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='正式消息素材的归属、对象定位及完整性元数据';

CREATE TABLE message_content_block (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '正式消息内容块 ID',
    message_id BIGINT UNSIGNED NOT NULL COMMENT '所属正式消息 ID',
    block_no INT UNSIGNED NOT NULL COMMENT '消息内从 1 开始的内容块顺序',
    block_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TEXT 或 MEDIA；素材具体类型由媒体记录表达',
    text_content LONGTEXT NULL COMMENT '文字块原文；素材块为空',
    media_id BIGINT UNSIGNED NULL COMMENT '素材块引用的 message_media ID；文字块为空',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_block_no (message_id, block_no),
    UNIQUE KEY uk_media_id (media_id),
    CONSTRAINT chk_message_block_no CHECK (block_no > 0),
    CONSTRAINT chk_message_block_payload CHECK (
        (block_kind = 'TEXT' AND text_content IS NOT NULL AND media_id IS NULL)
        OR (block_kind = 'MEDIA' AND text_content IS NULL AND media_id IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='正式消息内有序文字与通用素材内容块';

CREATE TABLE business_run (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '一次执行或显式重试的业务 RunId',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 SessionId',
    input_message_id BIGINT UNSIGNED NOT NULL COMMENT '本次执行引用的已接纳正式用户消息 ID',
    actor_user_id BIGINT UNSIGNED NOT NULL COMMENT '本次调用的已登录 UserId',
    employee_release_id BIGINT UNSIGNED NOT NULL COMMENT '本轮固定的数字员工发布版本 ID',
    memory_generation_at_start BIGINT UNSIGNED NOT NULL COMMENT 'Run 创建时固定的长期记忆代次；该轮期间用户改记忆不影响此值',
    memory_revision_at_start BIGINT UNSIGNED NOT NULL COMMENT 'Run 创建时固定的 MEMORY.md 代内修订',
    memory_policy_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Run 创建时固定的记忆提取与合并策略版本',
    run_request_key BINARY(16) NOT NULL COMMENT '一次 Run 请求的 UUID 幂等键',
    request_sha256 BINARY(32) NOT NULL COMMENT '输入消息与执行动作的稳定语义摘要',
    retry_of_run_id BIGINT UNSIGNED NULL COMMENT '显式重试所接续的旧 RunId',
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACCEPTED' COMMENT 'Run 生命周期状态',
    busy_session_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN NULL ELSE session_id END) STORED COMMENT '同一 Session 活动 Run 的唯一键',
    started_at DATETIME(6) NULL COMMENT '开始执行时间，UTC',
    ended_at DATETIME(6) NULL COMMENT '终态时间，UTC',
    reconcile_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '状态或外部写操作待核对的受控原因码',
    state_version_before BIGINT UNSIGNED NULL COMMENT '执行前观测的框架状态版本；能力待集成核验',
    state_version_after BIGINT UNSIGNED NULL COMMENT '执行后观测的框架状态版本；能力待集成核验',
    result_candidate JSON NULL COMMENT '尚未提升为正式消息的完整候选答复',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_actor_run_request_key (actor_user_id, run_request_key),
    UNIQUE KEY uk_busy_session_id (busy_session_id),
    KEY idx_session_id_gmt_create (session_id, gmt_create),
    KEY idx_input_message_id (input_message_id),
    KEY idx_retry_of_run_id (retry_of_run_id),
    CONSTRAINT chk_business_run_status CHECK (status IN ('ACCEPTED', 'RUNNING', 'WAITING_CONFIRMATION', 'CANCEL_REQUESTED', 'RECONCILING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT chk_business_run_memory_snapshot CHECK (memory_generation_at_start > 0 AND memory_revision_at_start > 0 AND CHAR_LENGTH(TRIM(memory_policy_version)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次数字员工执行及其恢复状态';

CREATE TABLE run_progress (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Run 过程事件 ID',
    run_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 RunId',
    event_no BIGINT UNSIGNED NOT NULL COMMENT 'Run 内从 1 开始的持久事件序号',
    event_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TEXT_DELTA、STATUS、INTERRUPTED 或 ERROR',
    display_text LONGTEXT NULL COMMENT '已落盘的部分回答片段或受控进度说明',
    occurred_at DATETIME(6) NOT NULL COMMENT '过程事件发生时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_event_no (run_id, event_no),
    CONSTRAINT chk_run_progress_no CHECK (event_no > 0),
    CONSTRAINT chk_run_progress_kind CHECK (event_kind IN ('TEXT_DELTA', 'STATUS', 'INTERRUPTED', 'ERROR')),
    CONSTRAINT chk_run_progress_text CHECK (event_kind <> 'TEXT_DELTA' OR display_text IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可供重连读取的 Run 部分输出与关键进度';

CREATE TABLE tool_operation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '一次逻辑工具操作的提案及执行记录 ID，不等于一次 HTTP 尝试',
    run_id BIGINT UNSIGNED NOT NULL COMMENT '产生本次工具操作的业务 RunId',
    operation_no INT UNSIGNED NOT NULL COMMENT '同一 Run 内从 1 开始的工具操作编号',
    actor_user_id BIGINT UNSIGNED NOT NULL COMMENT '已登录操作者 UserId，不能从参数或模型文本指定',
    tool_provider_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受控工具提供方标识，如会议 MCP；不接受客户端地址',
    tool_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际调用的工具名称，不限定会议或 MCP',
    tool_binding_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变工具绑定版本，固定目标、契约、Adapter 和策略',
    arguments_schema_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化业务参数的 Schema 及规范化规则版本',
    effect_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'READ_ONLY 或 MUTATING，由受控工具策略确定',
    is_confirmation_required TINYINT UNSIGNED NOT NULL COMMENT '是否必须取得本次用户确认：1 是，0 否；由平台策略决定',
    identity_binding_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信身份绑定类型，如 PLATFORM_USER 或 MEETING_PHONE',
    identity_binding_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信身份绑定记录引用，不保存手机号或身份凭据原文',
    identity_binding_version BIGINT UNSIGNED NOT NULL COMMENT '本次调用依赖的身份绑定版本；由对应 Adapter 复核',
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端生成、无空格且跨尝试稳定的全局逻辑操作请求号',
    idempotency_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'NONE 或 PROVIDER_KEY，声明上游实际幂等能力',
    provider_request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '上游实际接受的稳定幂等键；无幂等能力时为空',
    reconciliation_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'REQUEST_ID、RESOURCE_ID 或 MANUAL，声明结果核对方式',
    normalized_arguments JSON NOT NULL COMMENT '提交确认及实际执行使用的规范化业务参数',
    arguments_sha256 BINARY(32) NOT NULL COMMENT '规范化参数原始字节的 SHA-256，不依赖 MySQL JSON 排序',
    operation_sha256 BINARY(32) NOT NULL COMMENT '操作目标、版本、操作者、身份、策略、请求号和参数的完整摘要',
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PROPOSED' COMMENT '通用操作生命周期；UNKNOWN 不等于失败',
    provider_result_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '上游归一化结果码',
    provider_resource_id VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '上游资源关联标识，如预约号、请假单号或邮件标识',
    normalized_result JSON NULL COMMENT '经 Adapter 归一化的必要业务结果，不保存凭据或原始 HTTP 报文',
    sent_at DATETIME(6) NULL COMMENT '首次发送意图持久化时间，UTC；不证明请求已被上游接收',
    resolved_at DATETIME(6) NULL COMMENT '上游结果确定时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_run_operation_no (run_id, operation_no),
    UNIQUE KEY uk_request_id (request_id(128)),
    KEY idx_run_id_status (run_id, status(24)),
    CONSTRAINT chk_tool_operation_no CHECK (operation_no > 0),
    CONSTRAINT chk_tool_operation_target CHECK (CHAR_LENGTH(TRIM(tool_provider_key)) > 0 AND CHAR_LENGTH(TRIM(tool_name)) > 0 AND CHAR_LENGTH(TRIM(tool_binding_version)) > 0 AND CHAR_LENGTH(TRIM(arguments_schema_version)) > 0),
    CONSTRAINT chk_tool_operation_effect CHECK (effect_kind IN ('READ_ONLY', 'MUTATING')),
    CONSTRAINT chk_tool_operation_confirmation CHECK (is_confirmation_required IN (0, 1)),
    CONSTRAINT chk_tool_operation_identity CHECK (CHAR_LENGTH(TRIM(identity_binding_kind)) > 0 AND CHAR_LENGTH(TRIM(identity_binding_ref)) > 0 AND identity_binding_version > 0),
    CONSTRAINT chk_tool_operation_status CHECK (status IN ('PROPOSED', 'READY_TO_SEND', 'SENT', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_tool_operation_request_id CHECK (CHAR_LENGTH(TRIM(request_id)) > 0 AND request_id NOT LIKE '% %'),
    CONSTRAINT chk_tool_operation_idempotency CHECK (
        (idempotency_mode = 'NONE' AND provider_request_id IS NULL)
        OR (idempotency_mode = 'PROVIDER_KEY' AND provider_request_id IS NOT NULL AND CHAR_LENGTH(TRIM(provider_request_id)) > 0)
    ),
    CONSTRAINT chk_tool_operation_reconciliation CHECK (reconciliation_mode IN ('REQUEST_ID', 'RESOURCE_ID', 'MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='跨业务的逻辑工具操作、确认提案与可核对执行结果';

CREATE TABLE operation_confirmation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '一次具体工具操作的用户确认 ID',
    operation_id BIGINT UNSIGNED NOT NULL COMMENT '对应 tool_operation 的不可变操作提案 ID',
    run_id BIGINT UNSIGNED NOT NULL COMMENT '提案所属业务 RunId',
    actor_user_id BIGINT UNSIGNED NOT NULL COMMENT '应作出确认的当前登录 UserId',
    arguments_sha256 BINARY(32) NOT NULL COMMENT '展示并确认的规范化参数摘要',
    operation_sha256 BINARY(32) NOT NULL COMMENT '展示并确认的完整操作摘要，包含目标、版本、身份和参数',
    authorization_version BIGINT UNSIGNED NOT NULL COMMENT '展示时记录的用户授权版本',
    security_version BIGINT UNSIGNED NOT NULL COMMENT '展示时记录的账号安全版本',
    identity_binding_version BIGINT UNSIGNED NOT NULL COMMENT '展示时记录的调用身份绑定版本，会议场景映射手机号版本',
    confirmation_token_sha256 BINARY(32) NOT NULL COMMENT '一次性随机确认凭据的摘要，不保存原文',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、CONSUMED、EXPIRED 或 INVALIDATED',
    expires_at DATETIME(6) NOT NULL COMMENT '展示后十分钟的确认截止时间，UTC',
    consumed_at DATETIME(6) NULL COMMENT '确认被一次性消费时间，UTC',
    invalid_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '失效的受控原因码，不保存自由文本参数',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_operation_id (operation_id),
    UNIQUE KEY uk_confirmation_token_sha256 (confirmation_token_sha256),
    KEY idx_run_id (run_id),
    CONSTRAINT chk_operation_confirmation_status CHECK (status IN ('PENDING', 'CONSUMED', 'EXPIRED', 'INVALIDATED')),
    CONSTRAINT chk_operation_confirmation_consumed CHECK (status <> 'CONSUMED' OR consumed_at IS NOT NULL),
    CONSTRAINT chk_operation_confirmation_version CHECK (authorization_version > 0 AND security_version > 0 AND identity_binding_version > 0),
    CONSTRAINT chk_operation_confirmation_expiry CHECK (expires_at > gmt_create)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用工具操作的具体内容展示和一次性用户确认';

CREATE TABLE conversation_audit_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话及管理员内容访问审计事件 ID',
    actor_user_id BIGINT UNSIGNED NULL COMMENT '操作人 UserId；系统空闲过期等自动动作允许为空',
    target_user_id BIGINT UNSIGNED NOT NULL COMMENT '被查看或受操作影响的会话所有者 UserId',
    session_id BIGINT UNSIGNED NULL COMMENT '关联业务 SessionId',
    run_id BIGINT UNSIGNED NULL COMMENT '关联业务 RunId',
    operation_id BIGINT UNSIGNED NULL COMMENT '关联通用 tool_operation ID',
    employee_id BIGINT UNSIGNED NULL COMMENT '记忆或资料动作关联的数字员工 ID；与会话、消息动作无关时为空',
    memory_generation BIGINT UNSIGNED NULL COMMENT '记忆动作发生时的有效代次；手工换代记录新代次',
    document_revision_before BIGINT UNSIGNED NULL COMMENT '正文修订变化前的代内修订，与 document_revision_after 同空同非空；不复制旧偏好正文',
    document_revision_after BIGINT UNSIGNED NULL COMMENT '正文修订变化后的代内修订',
    memory_update_job_id BIGINT UNSIGNED NULL COMMENT '关联 memory_update_job ID，逻辑引用，无外键',
    action_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '升级、归档、取消、恢复、记忆或资料变更、管理员查看等受控动作码，集合由应用约束',
    outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SUCCESS 或 FAILURE',
    entry_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'WEB、ADMIN_WEB 或 SYSTEM',
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '拒绝或失败的受控原因码，不复制被查看正文',
    occurred_at DATETIME(6) NOT NULL COMMENT '审计事件发生时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    KEY idx_actor_user_id_occurred_at (actor_user_id, occurred_at),
    KEY idx_target_user_id_occurred_at (target_user_id, occurred_at),
    KEY idx_session_id_occurred_at (session_id, occurred_at),
    CONSTRAINT chk_conversation_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT chk_conversation_audit_entry CHECK (entry_kind IN ('WEB', 'ADMIN_WEB', 'SYSTEM')),
    CONSTRAINT chk_conversation_audit_admin_actor CHECK (entry_kind <> 'ADMIN_WEB' OR actor_user_id IS NOT NULL),
    CONSTRAINT chk_conversation_audit_revision_pair CHECK ((document_revision_before IS NULL) = (document_revision_after IS NULL)),
    CONSTRAINT chk_conversation_audit_revision_generation CHECK (document_revision_before IS NULL OR memory_generation IS NOT NULL),
    CONSTRAINT chk_conversation_audit_memory_employee CHECK ((memory_generation IS NULL AND memory_update_job_id IS NULL) OR employee_id IS NOT NULL),
    CONSTRAINT chk_conversation_audit_memory_positive CHECK (employee_id > 0 AND memory_generation > 0 AND document_revision_before > 0
        AND document_revision_after > 0 AND memory_update_job_id > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话动作与管理员内容访问的结构化追加审计';
