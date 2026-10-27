-- 阶段 1：Run 调度、可信 SDK 调用与独立终结证据控制表；并依赖已含 WAITING_INPUT／row_version 的 business_run。
-- 依据后端最小数据调整与初始化契约 §4—6／§13 及接口锁序 §9—10 子序列；适用于 MySQL 8.0.16+。
-- 须在已执行 01～02（至少含 platform_user／business_session／business_message／business_run）的空库或合成探针库上追加。
-- 无物理外键、触发器、级联或存储过程；正式派发循环、真实 SDK／Store、容量调度生产实现不在本 DDL 范围。
-- 所有时间由应用按 UTC 写入；不保存 SDK 对象、token 明文或完整敏感报文。

CREATE TABLE runtime_capacity (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '容量控制行主键',
    singleton_key TINYINT UNSIGNED NOT NULL COMMENT '单例键，恒为 1',
    foreground_limit INT UNSIGNED NOT NULL COMMENT '前台执行许可上限；应用配置初始值，不写死在 CHECK',
    normal_waiting_limit INT UNSIGNED NOT NULL COMMENT '普通新任务等待上限；应用配置初始值，不写死在 CHECK',
    occupied_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前 HELD 许可计数',
    normal_waiting_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前计入普通等待额度的行数',
    boot_epoch BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '启动代次；新 epoch 拒绝陈旧领取条件',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '条件更新修订，从 1 开始',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_runtime_capacity_singleton (singleton_key),
    CONSTRAINT chk_runtime_capacity_singleton CHECK (singleton_key = 1),
    CONSTRAINT chk_runtime_capacity_limits CHECK (foreground_limit > 0 AND normal_waiting_limit > 0),
    CONSTRAINT chk_runtime_capacity_counts CHECK (
        occupied_count <= foreground_limit AND normal_waiting_count <= normal_waiting_limit
    ),
    CONSTRAINT chk_runtime_capacity_version CHECK (boot_epoch > 0 AND row_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='前台许可与普通等待容量的单例互斥入口 C0';

CREATE TABLE runtime_dispatch (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '持久调度事实主键',
    dispatch_key BINARY(16) NOT NULL COMMENT '调度 UUID，对外稳定标识',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '调度所属稳定 UserId',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '数字员工 ID',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 SessionId',
    run_id BIGINT UNSIGNED NOT NULL COMMENT '关联业务 RunId',
    employee_release_id BIGINT UNSIGNED NOT NULL COMMENT '固定发布版本 ID',
    dispatch_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ORDINARY／CONTINUATION',
    work_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '原始运行／FORM 续接／明确继续／确认后工具交接等',
    source_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定逻辑来源类型',
    source_id BIGINT UNSIGNED NOT NULL COMMENT '稳定逻辑来源 ID',
    source_version BIGINT UNSIGNED NOT NULL COMMENT '稳定逻辑来源版本',
    request_key BINARY(16) NOT NULL COMMENT '调度请求幂等键 UUID',
    request_sha256 BINARY(32) NOT NULL COMMENT '请求规范语义摘要',
    response_id BIGINT UNSIGNED NULL COMMENT '可空 FORM 响应引用',
    operation_id BIGINT UNSIGNED NULL COMMENT '可空工具操作引用',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'WAITING／CLAIMED／COMPLETED／CANCELLED／RECONCILING／PAUSED',
    permit_state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'NONE／HELD／RELEASED',
    counts_toward_waiting TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '1=已纳入普通等待额度；仅 ORDINARY＋WAITING＋NONE 可置 1',
    boot_epoch BIGINT UNSIGNED NOT NULL COMMENT '领取／排队时绑定的启动代次',
    claim_fence BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '领取围栏',
    claim_token BINARY(16) NULL COMMENT '领取令牌；CLAIMED 时非空',
    claimed_at DATETIME(6) NULL COMMENT '领取时间，UTC',
    finished_at DATETIME(6) NULL COMMENT '完成或取消时间，UTC',
    sdk_call_id BIGINT UNSIGNED NULL COMMENT '关联可信调用行；可空',
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '暂停／核对／取消受控原因码',
    result_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '结果引用标识；不保存 SDK 对象',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '并发控制版本',
    held_session_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN permit_state = 'HELD' THEN session_id ELSE NULL END) STORED COMMENT '同一 Session 至多一个 HELD 许可',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dispatch_key (dispatch_key),
    UNIQUE KEY uk_dispatch_source_action (source_kind(32), source_id, source_version, work_kind(32)),
    UNIQUE KEY uk_dispatch_held_session (held_session_id),
    KEY idx_dispatch_status_created (status(16), gmt_create),
    KEY idx_dispatch_session_run (session_id, run_id),
    KEY idx_dispatch_boot_permit (boot_epoch, permit_state(16)),
    KEY idx_dispatch_waiting (dispatch_kind(16), status(16), counts_toward_waiting),
    KEY idx_dispatch_reconciling (status(16), claim_fence),
    CONSTRAINT chk_runtime_dispatch_positive CHECK (
        user_id > 0 AND employee_id > 0 AND session_id > 0 AND run_id > 0
        AND employee_release_id > 0 AND source_id > 0 AND source_version > 0
        AND boot_epoch > 0 AND row_version > 0 AND counts_toward_waiting IN (0, 1)
        AND (response_id IS NULL OR response_id > 0)
        AND (operation_id IS NULL OR operation_id > 0)
        AND (sdk_call_id IS NULL OR sdk_call_id > 0)
    ),
    CONSTRAINT chk_runtime_dispatch_kind CHECK (dispatch_kind IN ('ORDINARY', 'CONTINUATION')),
    CONSTRAINT chk_runtime_dispatch_work CHECK (CHAR_LENGTH(TRIM(work_kind)) > 0),
    CONSTRAINT chk_runtime_dispatch_source CHECK (CHAR_LENGTH(TRIM(source_kind)) > 0),
    CONSTRAINT chk_runtime_dispatch_status CHECK (
        status IN ('WAITING', 'CLAIMED', 'COMPLETED', 'CANCELLED', 'RECONCILING', 'PAUSED')
    ),
    CONSTRAINT chk_runtime_dispatch_permit CHECK (permit_state IN ('NONE', 'HELD', 'RELEASED')),
    CONSTRAINT chk_runtime_dispatch_waiting_count CHECK (
        counts_toward_waiting = 0
        OR (dispatch_kind = 'ORDINARY' AND status = 'WAITING' AND permit_state = 'NONE')
    ),
    CONSTRAINT chk_runtime_dispatch_held CHECK (
        (permit_state = 'HELD' AND status IN ('CLAIMED', 'RECONCILING') AND claim_token IS NOT NULL AND claimed_at IS NOT NULL)
        OR (permit_state <> 'HELD')
    ),
    CONSTRAINT chk_runtime_dispatch_claimed CHECK (
        (status = 'CLAIMED' AND claim_token IS NOT NULL AND claimed_at IS NOT NULL AND permit_state = 'HELD')
        OR status <> 'CLAIMED'
    ),
    CONSTRAINT chk_runtime_dispatch_finished CHECK (
        (status IN ('COMPLETED', 'CANCELLED') AND finished_at IS NOT NULL AND permit_state IN ('NONE', 'RELEASED'))
        OR status NOT IN ('COMPLETED', 'CANCELLED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='普通新任务与续接的持久调度事实，不是业务 Run';

CREATE TABLE runtime_sdk_call (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '可信 SDK 调用证据主键',
    sdk_call_key BINARY(16) NOT NULL COMMENT '平台生成的调用 UUID，不可用框架 replyId 代替',
    run_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 RunId',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 SessionId',
    dispatch_id BIGINT UNSIGNED NOT NULL COMMENT '所属调度事实 ID',
    call_no INT UNSIGNED NOT NULL COMMENT '同一 Run 内从 1 开始的调用序号',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '调用所有者稳定 UserId',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '数字员工 ID',
    employee_release_id BIGINT UNSIGNED NOT NULL COMMENT '固定发布版本 ID',
    state_session_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '可信状态槽；须匹配业务 Session',
    state_generation BIGINT UNSIGNED NOT NULL COMMENT '平台状态槽／执行代次；不使用 memory_generation 冒充',
    sdk_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定 SDK 版本标识',
    boot_epoch BIGINT UNSIGNED NOT NULL COMMENT '领取时启动代次',
    claim_fence BIGINT UNSIGNED NOT NULL COMMENT '写回围栏',
    executor_ref VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '执行者标识；不保存凭据',
    started_at DATETIME(6) NULL COMMENT 'STARTED 时间，UTC',
    ended_at DATETIME(6) NULL COMMENT '结束时间，UTC',
    call_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '调用种类，如 ORDINARY／FORM_CONTINUE',
    input_sha256 BINARY(32) NOT NULL COMMENT '输入规范摘要',
    response_id BIGINT UNSIGNED NULL COMMENT '可空交互响应引用',
    pending_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原 pending 标识',
    pending_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原 pending 名称',
    frozen_tool_use_sha256 BINARY(32) NULL COMMENT '冻结 ToolUse 摘要',
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PREPARED／STARTED／RETURNED／VERIFIED／RECONCILING／FAILED_BEFORE_START',
    outcome_kind VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '完整结果／异常类别码',
    store_version_capability VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'UNAVAILABLE／VERSIONED；未取得能力不得伪造正版本',
    before_exists TINYINT UNSIGNED NOT NULL COMMENT '执行前状态是否存在：1 是，0 否',
    before_store_version BIGINT UNSIGNED NULL COMMENT '执行前 Store 版本；无能力时必须为空',
    before_state_sha256 BINARY(32) NULL COMMENT '执行前状态摘要',
    before_evidence_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '受控只读前证据引用',
    save_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE' COMMENT 'NONE／SAVED／FAILED／UNCLEAR',
    saved_store_version BIGINT UNSIGNED NULL COMMENT '保存后 Store 版本',
    saved_state_sha256 BINARY(32) NULL COMMENT '保存后状态摘要',
    save_receipt_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '真实保存收据引用',
    readback_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE' COMMENT 'NONE／MATCHED／MISMATCH／MISSING／UNCLEAR',
    readback_store_version BIGINT UNSIGNED NULL COMMENT '独立读回版本',
    readback_state_sha256 BINARY(32) NULL COMMENT '独立读回摘要',
    verified_at DATETIME(6) NULL COMMENT '核验完成时间，UTC',
    evidence_format_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '证据格式版本，从 1 开始',
    bootstrap_state_proof JSON NULL COMMENT '仅新 Session 首条已接纳 Run 首调用的空状态证明',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '并发控制版本',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sdk_call_key (sdk_call_key),
    UNIQUE KEY uk_run_call_no (run_id, call_no),
    KEY idx_sdk_call_session_generation (session_id, state_session_key(128), state_generation),
    KEY idx_sdk_call_dispatch (dispatch_id),
    KEY idx_sdk_call_response (response_id),
    KEY idx_sdk_call_status (status(32), verified_at),
    CONSTRAINT chk_runtime_sdk_call_positive CHECK (
        run_id > 0 AND session_id > 0 AND dispatch_id > 0 AND call_no > 0
        AND user_id > 0 AND employee_id > 0 AND employee_release_id > 0
        AND state_generation > 0 AND boot_epoch > 0 AND claim_fence >= 0
        AND row_version > 0 AND evidence_format_version > 0
        AND before_exists IN (0, 1)
        AND (response_id IS NULL OR response_id > 0)
    ),
    CONSTRAINT chk_runtime_sdk_call_slot CHECK (CHAR_LENGTH(TRIM(state_session_key)) > 0),
    CONSTRAINT chk_runtime_sdk_call_sdk CHECK (CHAR_LENGTH(TRIM(sdk_version)) > 0 AND CHAR_LENGTH(TRIM(executor_ref)) > 0),
    CONSTRAINT chk_runtime_sdk_call_kind CHECK (CHAR_LENGTH(TRIM(call_kind)) > 0),
    CONSTRAINT chk_runtime_sdk_call_status CHECK (
        status IN ('PREPARED', 'STARTED', 'RETURNED', 'VERIFIED', 'RECONCILING', 'FAILED_BEFORE_START')
    ),
    CONSTRAINT chk_runtime_sdk_call_capability CHECK (store_version_capability IN ('UNAVAILABLE', 'VERSIONED')),
    CONSTRAINT chk_runtime_sdk_call_save_status CHECK (save_status IN ('NONE', 'SAVED', 'FAILED', 'UNCLEAR')),
    CONSTRAINT chk_runtime_sdk_call_readback_status CHECK (
        readback_status IN ('NONE', 'MATCHED', 'MISMATCH', 'MISSING', 'UNCLEAR')
    ),
    CONSTRAINT chk_runtime_sdk_call_version_fields CHECK (
        (before_store_version IS NULL OR before_store_version > 0)
        AND (saved_store_version IS NULL OR saved_store_version > 0)
        AND (readback_store_version IS NULL OR readback_store_version > 0)
    ),
    CONSTRAINT chk_runtime_sdk_call_unversioned CHECK (
        store_version_capability = 'VERSIONED'
        OR (
            before_store_version IS NULL AND saved_store_version IS NULL AND readback_store_version IS NULL
        )
    ),
    CONSTRAINT chk_runtime_sdk_call_bootstrap CHECK (
        bootstrap_state_proof IS NULL OR JSON_TYPE(bootstrap_state_proof) = 'OBJECT'
    ),
    CONSTRAINT chk_runtime_sdk_call_verified CHECK (
        status <> 'VERIFIED'
        OR (
            save_status = 'SAVED'
            AND readback_status = 'MATCHED'
            AND verified_at IS NOT NULL
            AND saved_state_sha256 IS NOT NULL
            AND readback_state_sha256 IS NOT NULL
            AND saved_state_sha256 = readback_state_sha256
            AND (
                store_version_capability = 'UNAVAILABLE'
                OR (
                    saved_store_version IS NOT NULL
                    AND readback_store_version IS NOT NULL
                    AND saved_store_version = readback_store_version
                )
            )
        )
    ),
    CONSTRAINT chk_runtime_sdk_call_prepared CHECK (
        status <> 'PREPARED' OR (started_at IS NULL AND ended_at IS NULL AND save_status = 'NONE' AND readback_status = 'NONE')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一次受控 SDK 调用的归属、fence、保存与独立读回证据';

CREATE TABLE runtime_state_terminalization (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '独立挂起终结证据主键',
    terminalization_key BINARY(16) NOT NULL COMMENT '终结键 UUID，全局唯一',
    sdk_call_id BIGINT UNSIGNED NOT NULL COMMENT '原调用证据行；终结不得覆盖该行前后字段',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所有者稳定 UserId',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 SessionId',
    run_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 RunId',
    state_session_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标状态槽',
    state_generation BIGINT UNSIGNED NOT NULL COMMENT '目标执行代次',
    pending_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标 pending 标识',
    pending_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '目标 pending 名称',
    frozen_tool_use_sha256 BINARY(32) NOT NULL COMMENT '冻结 ToolUse 摘要',
    quiescence_proof_sha256 BINARY(32) NOT NULL COMMENT '旧调用静止／退出证明摘要',
    boot_epoch BIGINT UNSIGNED NOT NULL COMMENT '终结时启动代次',
    claim_fence BIGINT UNSIGNED NOT NULL COMMENT '终结写回围栏',
    before_store_version BIGINT UNSIGNED NULL COMMENT '终结前 Store 版本',
    before_state_sha256 BINARY(32) NOT NULL COMMENT '终结前状态摘要',
    after_store_version BIGINT UNSIGNED NULL COMMENT '终结后 Store 版本',
    after_state_sha256 BINARY(32) NULL COMMENT '终结后状态摘要',
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '终结原因码',
    zero_execution_proof_sha256 BINARY(32) NOT NULL COMMENT '无模型／工具执行证明摘要',
    readback_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE' COMMENT 'NONE／MATCHED／MISMATCH／MISSING／UNCLEAR',
    readback_store_version BIGINT UNSIGNED NULL COMMENT '独立读回版本',
    readback_state_sha256 BINARY(32) NULL COMMENT '独立读回摘要',
    evidence_format_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '证据格式版本，从 1 开始',
    store_version_capability VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'UNAVAILABLE／VERSIONED',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PREPARED／APPLIED／VERIFIED／RECONCILING',
    applied_at DATETIME(6) NULL COMMENT 'APPLIED 时间，UTC',
    verified_at DATETIME(6) NULL COMMENT 'VERIFIED 时间，UTC',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '条件转换版本',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_terminalization_key (terminalization_key),
    UNIQUE KEY uk_terminalization_sdk_call (sdk_call_id),
    KEY idx_terminalization_session_run (session_id, run_id),
    KEY idx_terminalization_status (status(16), verified_at),
    KEY idx_terminalization_slot (state_session_key(128), state_generation),
    CONSTRAINT chk_runtime_term_positive CHECK (
        sdk_call_id > 0 AND user_id > 0 AND session_id > 0 AND run_id > 0
        AND state_generation > 0 AND boot_epoch > 0 AND claim_fence >= 0
        AND row_version > 0 AND evidence_format_version > 0
    ),
    CONSTRAINT chk_runtime_term_slot CHECK (CHAR_LENGTH(TRIM(state_session_key)) > 0),
    CONSTRAINT chk_runtime_term_pending CHECK (
        CHAR_LENGTH(TRIM(pending_id)) > 0 AND CHAR_LENGTH(TRIM(pending_name)) > 0
        AND CHAR_LENGTH(TRIM(reason_code)) > 0
    ),
    CONSTRAINT chk_runtime_term_status CHECK (
        status IN ('PREPARED', 'APPLIED', 'VERIFIED', 'RECONCILING')
    ),
    CONSTRAINT chk_runtime_term_capability CHECK (store_version_capability IN ('UNAVAILABLE', 'VERSIONED')),
    CONSTRAINT chk_runtime_term_readback_status CHECK (
        readback_status IN ('NONE', 'MATCHED', 'MISMATCH', 'MISSING', 'UNCLEAR')
    ),
    CONSTRAINT chk_runtime_term_version_fields CHECK (
        (before_store_version IS NULL OR before_store_version > 0)
        AND (after_store_version IS NULL OR after_store_version > 0)
        AND (readback_store_version IS NULL OR readback_store_version > 0)
    ),
    CONSTRAINT chk_runtime_term_unversioned CHECK (
        store_version_capability = 'VERSIONED'
        OR (before_store_version IS NULL AND after_store_version IS NULL AND readback_store_version IS NULL)
    ),
    CONSTRAINT chk_runtime_term_prepared CHECK (
        status <> 'PREPARED' OR (applied_at IS NULL AND verified_at IS NULL AND after_state_sha256 IS NULL AND readback_status = 'NONE')
    ),
    CONSTRAINT chk_runtime_term_applied CHECK (
        status <> 'APPLIED'
        OR (applied_at IS NOT NULL AND after_state_sha256 IS NOT NULL AND verified_at IS NULL)
    ),
    CONSTRAINT chk_runtime_term_verified CHECK (
        status <> 'VERIFIED'
        OR (
            applied_at IS NOT NULL
            AND verified_at IS NOT NULL
            AND after_state_sha256 IS NOT NULL
            AND readback_status = 'MATCHED'
            AND readback_state_sha256 IS NOT NULL
            AND after_state_sha256 = readback_state_sha256
            AND (
                store_version_capability = 'UNAVAILABLE'
                OR (
                    after_store_version IS NOT NULL
                    AND readback_store_version IS NOT NULL
                    AND after_store_version = readback_store_version
                )
            )
        )
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='原调用之外的受控挂起终结及前后证明；不计为模型 SDK 调用';
