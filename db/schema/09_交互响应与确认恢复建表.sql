-- 阶段 1 / 09，依据 #22 数据契约 §9/§11/§13 与 #19 §8。依赖 01～05、08。
-- 仅用于空库顺序建表或执行器创建的合成隔离库；不是存量迁移入口。
-- 无物理外键、触发器、级联或存储过程。全部时间为 UTC；不存确认 token 原文或 keyring。
-- CHECK 只约束单行；跨行引用、不可变字段和不可逆转换由受控条件 SQL 与 25 号巡检验证。

CREATE TABLE interaction_request (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '逻辑交互请求主键',
    public_request_id BINARY(16) NOT NULL COMMENT '服务端生成的公开不透明 UUID',
    owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '请求所有者稳定 UserId',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '原业务 SessionId',
    run_id BIGINT UNSIGNED NOT NULL COMMENT '原业务 RunId；回答不新建 Run',
    employee_release_id BIGINT UNSIGNED NOT NULL COMMENT '原 Run 固定员工发布版本',
    run_request_no INT UNSIGNED NOT NULL COMMENT 'Run 内逻辑请求序号，从 1 开始',
    interaction_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'FORM／OPERATION_CONFIRMATION',
    current_revision_id BIGINT UNSIGNED NULL COMMENT '当前不可变展示修订主键；PREPARING 可空',
    revision_no INT UNSIGNED NULL COMMENT '当前展示修订号，与指针成对',
    source_sdk_call_id BIGINT UNSIGNED NULL COMMENT '产生原 pending 的可信 SDK 调用证据',
    pending_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '原 native pending 标识',
    pending_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '受控 pending 工具名',
    frozen_tool_use_sha256 BINARY(32) NULL COMMENT '原冻结 ToolUse 摘要',
    ready_checkpoint_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '就绪 checkpoint 证据引用；不含 SDK 状态正文',
    ready_checkpoint_sha256 BINARY(32) NULL COMMENT '就绪时独立读回状态摘要',
    confirmation_id BIGINT UNSIGNED NULL COMMENT '原一次性操作确认；外壳不形成新授权',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PREPARING' COMMENT 'PREPARING／WAITING／ANSWERED／INVALIDATED／EXPIRED／RECONCILING',
    correction_count TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '结构修正次数，至多一次；重启不清零',
    authorization_version BIGINT UNSIGNED NOT NULL COMMENT '建立交互时的授权版本',
    security_version BIGINT UNSIGNED NOT NULL COMMENT '建立交互时的安全版本',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '状态与指针条件更新版本',
    invalid_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '受控失效原因码',
    active_run_id BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN status IN ('PREPARING','WAITING','RECONCILING') THEN run_id ELSE NULL END) STORED COMMENT '两类交互共享的活动 Run 唯一边界',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_interaction_public (public_request_id),
    UNIQUE KEY uk_interaction_run_no (run_id,run_request_no),
    UNIQUE KEY uk_interaction_active_run (active_run_id),
    UNIQUE KEY uk_interaction_confirmation (confirmation_id),
    KEY idx_interaction_owner_session (owner_user_id,session_id),
    KEY idx_interaction_status (status,run_id),
    KEY idx_interaction_source (source_sdk_call_id),
    CONSTRAINT chk_interaction_request_positive CHECK (
        owner_user_id>0 AND session_id>0 AND run_id>0 AND employee_release_id>0 AND run_request_no>0
        AND authorization_version>0 AND security_version>0 AND row_version>0 AND correction_count<=1
        AND (confirmation_id IS NULL OR confirmation_id>0)),
    CONSTRAINT chk_interaction_request_type CHECK (interaction_type IN ('FORM','OPERATION_CONFIRMATION')),
    CONSTRAINT chk_interaction_request_status CHECK (status IN ('PREPARING','WAITING','ANSWERED','INVALIDATED','EXPIRED','RECONCILING')),
    CONSTRAINT chk_interaction_request_revision CHECK (
        (current_revision_id IS NULL AND revision_no IS NULL)
        OR (current_revision_id IS NOT NULL AND current_revision_id>0 AND revision_no IS NOT NULL AND revision_no>0)),
    CONSTRAINT chk_interaction_request_ready CHECK (status NOT IN ('WAITING','ANSWERED') OR current_revision_id IS NOT NULL),
    CONSTRAINT chk_interaction_request_pending CHECK (
        (source_sdk_call_id IS NULL AND pending_id IS NULL AND pending_name IS NULL
            AND frozen_tool_use_sha256 IS NULL AND ready_checkpoint_ref IS NULL AND ready_checkpoint_sha256 IS NULL)
        OR (source_sdk_call_id IS NOT NULL AND source_sdk_call_id>0 AND pending_id IS NOT NULL
            AND CHAR_LENGTH(TRIM(pending_id))>0 AND pending_name IS NOT NULL AND CHAR_LENGTH(TRIM(pending_name))>0
            AND frozen_tool_use_sha256 IS NOT NULL AND ready_checkpoint_ref IS NOT NULL
            AND CHAR_LENGTH(TRIM(ready_checkpoint_ref))>0 AND ready_checkpoint_sha256 IS NOT NULL)),
    CONSTRAINT chk_interaction_request_form CHECK (interaction_type<>'FORM' OR (
        confirmation_id IS NULL AND status<>'EXPIRED'
        AND (status NOT IN ('WAITING','ANSWERED') OR source_sdk_call_id IS NOT NULL))),
    CONSTRAINT chk_interaction_request_confirmation CHECK (
        interaction_type<>'OPERATION_CONFIRMATION' OR status NOT IN ('WAITING','ANSWERED') OR confirmation_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='逻辑交互请求、原 pending 与当前展示指针';

CREATE TABLE interaction_revision (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '展示修订主键',
    request_id BIGINT UNSIGNED NOT NULL COMMENT '所属逻辑请求',
    revision_no INT UNSIGNED NOT NULL COMMENT '请求内从 1 开始的不可变修订号',
    interaction_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'FORM／OPERATION_CONFIRMATION，与请求一致',
    contract_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'FORM 固定 form-v1；确认使用受控 Adapter 展示版本',
    schema_dialect VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FORM 固定 draft-07；确认为空',
    snapshot_body MEDIUMBLOB NOT NULL COMMENT '精确 UTF-8 展示字节；FORM 含 data_schema／ui_schema／prefill／候选及来源；确认含固定提案',
    snapshot_sha256 BINARY(32) NOT NULL COMMENT 'snapshot_body 原始字节摘要，不经 JSON 重排',
    confirmation_id BIGINT UNSIGNED NULL COMMENT '确认展示引用原确认记录；FORM 为空',
    draft_data JSON NULL COMMENT '可变交互草稿，与不可变展示分区；不是正式消息',
    draft_revision BIGINT UNSIGNED NULL COMMENT 'FORM 草稿 CAS 修订，从 1 开始；确认为空',
    last_save_request_key BINARY(16) NULL COMMENT '最近成功保存草稿的稳定请求键',
    last_save_sha256 BINARY(32) NULL COMMENT '最近成功保存草稿的语义摘要',
    gmt_create DATETIME(6) NOT NULL COMMENT '修订创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '草稿最近保存时间，UTC；不授权修改展示',
    PRIMARY KEY (id),
    UNIQUE KEY uk_interaction_revision (request_id,revision_no),
    KEY idx_revision_confirmation (confirmation_id),
    CONSTRAINT chk_interaction_revision_positive CHECK (request_id>0 AND revision_no>0),
    CONSTRAINT chk_interaction_revision_body CHECK (OCTET_LENGTH(snapshot_body)>0 AND OCTET_LENGTH(snapshot_body)<=65536),
    CONSTRAINT chk_interaction_revision_type CHECK (
        (interaction_type='FORM' AND contract_version='form-v1' AND schema_dialect IS NOT NULL AND schema_dialect='draft-07'
            AND confirmation_id IS NULL AND draft_revision IS NOT NULL AND draft_revision>0)
        OR (interaction_type='OPERATION_CONFIRMATION' AND CHAR_LENGTH(TRIM(contract_version))>0 AND schema_dialect IS NULL
            AND confirmation_id IS NOT NULL AND confirmation_id>0 AND draft_data IS NULL AND draft_revision IS NULL
            AND last_save_request_key IS NULL AND last_save_sha256 IS NULL)),
    CONSTRAINT chk_interaction_draft_save_pair CHECK (
        (last_save_request_key IS NULL AND last_save_sha256 IS NULL)
        OR (last_save_request_key IS NOT NULL AND last_save_sha256 IS NOT NULL)),
    CONSTRAINT chk_interaction_draft_budget CHECK (draft_data IS NULL OR OCTET_LENGTH(CAST(draft_data AS CHAR CHARACTER SET utf8mb4))<=65536)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变交互展示及该修订独立的可变草稿区';

CREATE TABLE interaction_response (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '唯一交互响应主键',
    request_id BIGINT UNSIGNED NOT NULL COMMENT '逻辑请求，一次接纳后永久唯一',
    interaction_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'FORM／OPERATION_CONFIRMATION，与请求一致',
    actor_user_id BIGINT UNSIGNED NOT NULL COMMENT '可信已登录提交者，与请求 owner 一致',
    submission_key BINARY(16) NOT NULL COMMENT 'actor 范围内稳定提交 UUID',
    submission_sha256 BINARY(32) NOT NULL COMMENT '固定接纳语义摘要，不含确认 token',
    accepted_revision_id BIGINT UNSIGNED NOT NULL COMMENT '实际接纳的展示修订主键',
    snapshot_sha256 BINARY(32) NOT NULL COMMENT '实际接纳的不可变展示摘要',
    raw_data JSON NULL COMMENT 'FORM 原始回答；确认为空，不存 token',
    normalized_data JSON NULL COMMENT 'FORM 规范化回答；确认为空',
    readable_text MEDIUMBLOB NULL COMMENT 'FORM 可读回答 UTF-8 字节；确认为空',
    accepted_message_id BIGINT UNSIGNED NULL COMMENT 'FORM 原 Run 正式 USER 消息；确认必须为空',
    accepted_at DATETIME(6) NOT NULL COMMENT '唯一响应接纳时间，UTC',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'FORM ACCEPTED／CLAIMED／CONSUMED／RECONCILING；确认仅 CONSUMED',
    sdk_call_id BIGINT UNSIGNED NULL COMMENT 'FORM 唯一领取调用证据；不复用源调用',
    claim_fence BIGINT UNSIGNED NULL COMMENT 'FORM 领取围栏',
    before_checkpoint_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FORM 消费前证据引用',
    before_checkpoint_sha256 BINARY(32) NULL COMMENT 'FORM 消费前状态摘要',
    after_checkpoint_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'FORM 消费后独立读回证据引用',
    after_checkpoint_sha256 BINARY(32) NULL COMMENT 'FORM 消费后状态摘要',
    consumed_at DATETIME(6) NULL COMMENT 'FORM 真实消费证明或原确认授权消费时间，UTC；不是工具成功',
    continue_request_key BINARY(16) NULL COMMENT '最近明确继续的去重请求键',
    continue_request_sha256 BINARY(32) NULL COMMENT '明确继续语义摘要',
    sdk_sync_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '确认之后可选 SDK 同步：PENDING／SYNCED／RECONCILING',
    sdk_sync_call_id BIGINT UNSIGNED NULL COMMENT '确认后实际 SDK 同步调用；不是 FORM 消费',
    sdk_sync_evidence_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '确认后同步证据引用',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '响应领取和收尾条件更新版本',
    gmt_create DATETIME(6) NOT NULL COMMENT '响应记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '响应记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_response_request (request_id),
    UNIQUE KEY uk_response_actor_submission (actor_user_id,submission_key),
    UNIQUE KEY uk_response_message (accepted_message_id),
    KEY idx_response_status (status,id),
    KEY idx_response_sdk_call (sdk_call_id),
    KEY idx_response_revision (accepted_revision_id),
    CONSTRAINT chk_interaction_response_positive CHECK (
        request_id>0 AND actor_user_id>0 AND accepted_revision_id>0 AND row_version>0
        AND (accepted_message_id IS NULL OR accepted_message_id>0)
        AND (sdk_sync_call_id IS NULL OR sdk_sync_call_id>0)),
    CONSTRAINT chk_interaction_response_claim CHECK (
        (sdk_call_id IS NULL AND claim_fence IS NULL AND before_checkpoint_ref IS NULL AND before_checkpoint_sha256 IS NULL)
        OR (sdk_call_id IS NOT NULL AND sdk_call_id>0 AND claim_fence IS NOT NULL AND claim_fence>0
            AND before_checkpoint_ref IS NOT NULL AND CHAR_LENGTH(TRIM(before_checkpoint_ref))>0 AND before_checkpoint_sha256 IS NOT NULL)),
    CONSTRAINT chk_interaction_response_after CHECK (
        (after_checkpoint_ref IS NULL AND after_checkpoint_sha256 IS NULL)
        OR (after_checkpoint_ref IS NOT NULL AND CHAR_LENGTH(TRIM(after_checkpoint_ref))>0 AND after_checkpoint_sha256 IS NOT NULL)),
    CONSTRAINT chk_interaction_response_continue CHECK (
        (continue_request_key IS NULL AND continue_request_sha256 IS NULL)
        OR (continue_request_key IS NOT NULL AND continue_request_sha256 IS NOT NULL)),
    CONSTRAINT chk_interaction_response_type CHECK (
        (interaction_type='FORM' AND status IN ('ACCEPTED','CLAIMED','CONSUMED','RECONCILING')
            AND accepted_message_id IS NOT NULL AND raw_data IS NOT NULL AND normalized_data IS NOT NULL AND readable_text IS NOT NULL
            AND OCTET_LENGTH(readable_text)<=65536 AND sdk_sync_status IS NULL AND sdk_sync_call_id IS NULL AND sdk_sync_evidence_ref IS NULL)
        OR (interaction_type='OPERATION_CONFIRMATION' AND status='CONSUMED' AND consumed_at IS NOT NULL
            AND accepted_message_id IS NULL AND raw_data IS NULL AND normalized_data IS NULL AND readable_text IS NULL
            AND sdk_call_id IS NULL AND claim_fence IS NULL AND before_checkpoint_ref IS NULL AND before_checkpoint_sha256 IS NULL
            AND after_checkpoint_ref IS NULL AND after_checkpoint_sha256 IS NULL
            AND continue_request_key IS NULL AND continue_request_sha256 IS NULL)),
    CONSTRAINT chk_interaction_response_form_state CHECK (interaction_type<>'FORM' OR (
        (status='ACCEPTED' AND sdk_call_id IS NULL AND after_checkpoint_ref IS NULL AND consumed_at IS NULL)
        OR (status='CLAIMED' AND sdk_call_id IS NOT NULL AND after_checkpoint_ref IS NULL AND consumed_at IS NULL)
        OR (status='CONSUMED' AND sdk_call_id IS NOT NULL AND after_checkpoint_ref IS NOT NULL AND consumed_at IS NOT NULL)
        OR (status='RECONCILING' AND consumed_at IS NULL))),
    CONSTRAINT chk_interaction_response_sync CHECK (
        (sdk_sync_status IS NULL AND sdk_sync_call_id IS NULL AND sdk_sync_evidence_ref IS NULL)
        OR (interaction_type='OPERATION_CONFIRMATION' AND sdk_sync_status IS NOT NULL AND sdk_sync_status IN ('PENDING','SYNCED','RECONCILING')
            AND (sdk_sync_status<>'SYNCED' OR (sdk_sync_call_id IS NOT NULL AND sdk_sync_evidence_ref IS NOT NULL AND CHAR_LENGTH(TRIM(sdk_sync_evidence_ref))>0)))),
    CONSTRAINT chk_interaction_response_time CHECK (consumed_at IS NULL OR consumed_at>=accepted_at),
    CONSTRAINT chk_interaction_answer_budget CHECK (
        (raw_data IS NULL OR OCTET_LENGTH(CAST(raw_data AS CHAR CHARACTER SET utf8mb4))<=65536)
        AND (normalized_data IS NULL OR OCTET_LENGTH(CAST(normalized_data AS CHAR CHARACTER SET utf8mb4))<=65536))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='交互唯一接纳、FORM 消费与确认后 SDK 同步事实';

CREATE TABLE crypto_nonce_cursor (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '独立 nonce 预留控制行',
    key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '外部 keyring 的固定 keyId，禁止重指密钥',
    nonce_prefix BINARY(4) NOT NULL COMMENT '与 keyId 固定绑定的四字节前缀',
    last_reserved_counter BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '最近已提交预留计数；零为未分配，上限 2^32-1',
    key_generation_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定外部密钥代次引用；不是密钥材料',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '预留 CAS 版本',
    gmt_create DATETIME(6) NOT NULL COMMENT '控制行创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '最近预留提交时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_nonce_key_id (key_id),
    CONSTRAINT chk_nonce_cursor_identity CHECK (CHAR_LENGTH(TRIM(key_id))>0 AND CHAR_LENGTH(TRIM(key_generation_ref))>0),
    CONSTRAINT chk_nonce_cursor_counter CHECK (last_reserved_counter<=4294967295 AND row_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单 keyId 独立短事务 nonce 预留游标；不嵌套领域事务';

-- 旧摘要行默认 MISSING，不伪造恢复副本；AVAILABLE 只说明字段齐全，不证明密钥或 GCM 可用。
-- BINARY(12) 会自动补零；应用必须在绑定 SQL 参数前检查原始字节数，SQL 再检列类型／配对。
ALTER TABLE operation_confirmation
    ADD COLUMN crypto_format_version SMALLINT UNSIGNED NULL COMMENT '恢复格式，目前仅 1',
    ADD COLUMN key_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '外部独立 keyring 的固定 keyId',
    ADD COLUMN token_nonce BINARY(12) NULL COMMENT '固定前缀四字节加八字节计数；不进入普通输出',
    ADD COLUMN token_ciphertext VARBINARY(48) NULL COMMENT '合成验收为占位字节；真实格式为 32 字节密文加 16 字节 GCM tag',
    ADD COLUMN recovery_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MISSING' COMMENT 'MISSING 旧摘要行／AVAILABLE 完整副本／UNAVAILABLE 不可恢复',
    ADD UNIQUE KEY uk_confirmation_key_nonce (key_id,token_nonce),
    ADD KEY idx_confirmation_recovery (recovery_status,expires_at),
    ADD CONSTRAINT chk_confirmation_recovery_status CHECK (recovery_status IN ('MISSING','AVAILABLE','UNAVAILABLE')),
    ADD CONSTRAINT chk_confirmation_crypto_pair CHECK (
        (crypto_format_version IS NULL AND key_id IS NULL AND token_nonce IS NULL AND token_ciphertext IS NULL AND recovery_status='MISSING')
        OR (crypto_format_version IS NOT NULL AND crypto_format_version=1 AND key_id IS NOT NULL AND CHAR_LENGTH(TRIM(key_id))>0
            AND token_nonce IS NOT NULL AND token_ciphertext IS NOT NULL AND OCTET_LENGTH(token_ciphertext)=48
            AND recovery_status IN ('AVAILABLE','UNAVAILABLE')));
