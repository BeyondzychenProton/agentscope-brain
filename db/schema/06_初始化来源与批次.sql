-- #29：仅在全新专用空库的 01～05 后执行一次。不是存量库迁移。
-- 平台初始化不创建管理员／系统账号；来源不是 principal。无外键、触发器和存储过程。
CREATE TABLE bootstrap_batch (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '初始化批次主键，不是用户身份',
    source_ref VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '公开稳定初始化来源编号，不含个人信息或凭据',
    bootstrap_scope VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '本期初始化范围，整个范围只允许一份批次',
    manifest_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '固定初始化清单编码版本',
    manifest_json JSON NULL COMMENT '脱敏稳定引用清单，无口令、完整手机号或当前状态',
    manifest_sha256 BINARY(32) NULL COMMENT '规范清单 UTF-8 字节摘要',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'PREPARING、APPLIED、FAILED 或 RECONCILING',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '条件更新修订，从一开始',
    program_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受控初始化实现版本',
    result_summary JSON NULL COMMENT '受控结果索引，含目标 ID 和成功审计关联',
    failure_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '受控失败分类，不含输入原文',
    created_at DATETIME(6) NOT NULL COMMENT '批次开始时间，UTC',
    applied_at DATETIME(6) NULL COMMENT '同平台事务应用时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bootstrap_source (source_ref(128)),
    UNIQUE KEY uk_bootstrap_scope (bootstrap_scope(64)),
    CONSTRAINT chk_bootstrap_text CHECK (CHAR_LENGTH(TRIM(source_ref)) > 0 AND CHAR_LENGTH(TRIM(bootstrap_scope)) > 0 AND CHAR_LENGTH(TRIM(program_version)) > 0),
    CONSTRAINT chk_bootstrap_version CHECK (manifest_version = 1 AND row_version > 0),
    CONSTRAINT chk_bootstrap_status CHECK (status IN ('PREPARING','APPLIED','FAILED','RECONCILING')),
    CONSTRAINT chk_bootstrap_manifest CHECK ((manifest_json IS NULL AND manifest_sha256 IS NULL) OR (manifest_json IS NOT NULL AND manifest_sha256 IS NOT NULL AND JSON_TYPE(manifest_json) = 'OBJECT')),
    CONSTRAINT chk_bootstrap_result CHECK (result_summary IS NULL OR JSON_TYPE(result_summary) = 'OBJECT'),
    CONSTRAINT chk_bootstrap_applied CHECK (status <> 'APPLIED' OR (manifest_json IS NOT NULL AND manifest_sha256 IS NOT NULL AND result_summary IS NOT NULL AND applied_at IS NOT NULL AND failure_reason IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='单范围系统初始化原子批次与脱敏清单';

-- USER_ACTION 默认保留人工动作语义；空 actor 必须有明确 SYSTEM_BOOTSTRAP 来源。
ALTER TABLE platform_user
    MODIFY created_by_user_id BIGINT UNSIGNED NULL COMMENT '真实操作者；完整系统来源时为空',
    ADD creation_source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER_ACTION' COMMENT '本动作来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD creation_bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '仅本次系统动作所属初始化批次',
    ADD KEY idx_bootstrap_source_1 (creation_bootstrap_batch_id),
    ADD CONSTRAINT chk_bootstrap_actor_1 CHECK (
        (creation_source_kind = 'USER_ACTION' AND created_by_user_id IS NOT NULL AND created_by_user_id > 0 AND creation_bootstrap_batch_id IS NULL)
        OR (creation_source_kind = 'SYSTEM_BOOTSTRAP' AND created_by_user_id IS NULL AND creation_bootstrap_batch_id IS NOT NULL AND creation_bootstrap_batch_id > 0));

ALTER TABLE platform_user
    MODIFY modified_by_user_id BIGINT UNSIGNED NULL COMMENT '真实操作者；完整系统来源时为空',
    ADD modification_source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER_ACTION' COMMENT '本动作来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD modification_bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '仅本次系统动作所属初始化批次',
    ADD KEY idx_bootstrap_source_2 (modification_bootstrap_batch_id),
    ADD CONSTRAINT chk_bootstrap_actor_2 CHECK (
        (modification_source_kind = 'USER_ACTION' AND modified_by_user_id IS NOT NULL AND modified_by_user_id > 0 AND modification_bootstrap_batch_id IS NULL)
        OR (modification_source_kind = 'SYSTEM_BOOTSTRAP' AND modified_by_user_id IS NULL AND modification_bootstrap_batch_id IS NOT NULL AND modification_bootstrap_batch_id > 0));

ALTER TABLE phone_reservation
    MODIFY ownership_checked_by BIGINT UNSIGNED NULL COMMENT '真实操作者；完整系统来源时为空',
    ADD ownership_source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER_ACTION' COMMENT '本动作来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD ownership_bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '仅本次系统动作所属初始化批次',
    ADD KEY idx_bootstrap_source_3 (ownership_bootstrap_batch_id),
    ADD CONSTRAINT chk_bootstrap_actor_3 CHECK (
        (ownership_source_kind = 'USER_ACTION' AND ownership_checked_by IS NOT NULL AND ownership_checked_by > 0 AND ownership_bootstrap_batch_id IS NULL)
        OR (ownership_source_kind = 'SYSTEM_BOOTSTRAP' AND ownership_checked_by IS NULL AND ownership_bootstrap_batch_id IS NOT NULL AND ownership_bootstrap_batch_id > 0));

ALTER TABLE employee_draft
    MODIFY saved_by BIGINT UNSIGNED NULL COMMENT '真实操作者；完整系统来源时为空',
    ADD save_source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER_ACTION' COMMENT '本动作来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD save_bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '仅本次系统动作所属初始化批次',
    ADD KEY idx_bootstrap_source_4 (save_bootstrap_batch_id),
    ADD CONSTRAINT chk_bootstrap_actor_4 CHECK (
        (save_source_kind = 'USER_ACTION' AND saved_by IS NOT NULL AND saved_by > 0 AND save_bootstrap_batch_id IS NULL)
        OR (save_source_kind = 'SYSTEM_BOOTSTRAP' AND saved_by IS NULL AND save_bootstrap_batch_id IS NOT NULL AND save_bootstrap_batch_id > 0));

ALTER TABLE employee_release
    MODIFY published_by BIGINT UNSIGNED NULL COMMENT '真实操作者；完整系统来源时为空',
    ADD creation_source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER_ACTION' COMMENT '本动作来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '仅本次系统动作所属初始化批次',
    ADD KEY idx_bootstrap_source_5 (bootstrap_batch_id),
    ADD CONSTRAINT chk_bootstrap_actor_5 CHECK (
        (creation_source_kind = 'USER_ACTION' AND published_by IS NOT NULL AND published_by > 0 AND bootstrap_batch_id IS NULL)
        OR (creation_source_kind = 'SYSTEM_BOOTSTRAP' AND published_by IS NULL AND bootstrap_batch_id IS NOT NULL AND bootstrap_batch_id > 0));

ALTER TABLE employee_release
    MODIFY availability_changed_by BIGINT UNSIGNED NULL COMMENT '真实操作者；完整系统来源时为空',
    ADD availability_source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER_ACTION' COMMENT '本动作来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD availability_bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '仅本次系统动作所属初始化批次',
    ADD KEY idx_bootstrap_source_6 (availability_bootstrap_batch_id),
    ADD CONSTRAINT chk_bootstrap_actor_6 CHECK (
        (availability_source_kind = 'USER_ACTION' AND availability_changed_by IS NOT NULL AND availability_changed_by > 0 AND availability_bootstrap_batch_id IS NULL)
        OR (availability_source_kind = 'SYSTEM_BOOTSTRAP' AND availability_changed_by IS NULL AND availability_bootstrap_batch_id IS NOT NULL AND availability_bootstrap_batch_id > 0));

ALTER TABLE tool_binding_revision
    MODIFY created_by BIGINT UNSIGNED NULL COMMENT '真实操作者；完整系统来源时为空',
    ADD creation_source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER_ACTION' COMMENT '本动作来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '仅本次系统动作所属初始化批次',
    ADD KEY idx_bootstrap_source_7 (bootstrap_batch_id),
    ADD CONSTRAINT chk_bootstrap_actor_7 CHECK (
        (creation_source_kind = 'USER_ACTION' AND created_by IS NOT NULL AND created_by > 0 AND bootstrap_batch_id IS NULL)
        OR (creation_source_kind = 'SYSTEM_BOOTSTRAP' AND created_by IS NULL AND bootstrap_batch_id IS NOT NULL AND bootstrap_batch_id > 0));

ALTER TABLE employee_use_grant
    MODIFY granted_by BIGINT UNSIGNED NULL COMMENT '真实操作者；完整系统来源时为空',
    ADD grant_source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER_ACTION' COMMENT '本动作来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD grant_bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '仅本次系统动作所属初始化批次',
    ADD KEY idx_bootstrap_source_8 (grant_bootstrap_batch_id),
    ADD CONSTRAINT chk_bootstrap_actor_8 CHECK (
        (grant_source_kind = 'USER_ACTION' AND granted_by IS NOT NULL AND granted_by > 0 AND grant_bootstrap_batch_id IS NULL)
        OR (grant_source_kind = 'SYSTEM_BOOTSTRAP' AND granted_by IS NULL AND grant_bootstrap_batch_id IS NOT NULL AND grant_bootstrap_batch_id > 0));

ALTER TABLE identity_audit_event
    MODIFY actor_user_id BIGINT UNSIGNED NULL COMMENT '真实操作者；完整系统来源时为空',
    ADD source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER_ACTION' COMMENT '本动作来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '仅本次系统动作所属初始化批次',
    ADD KEY idx_bootstrap_source_9 (bootstrap_batch_id),
    ADD CONSTRAINT chk_bootstrap_actor_9 CHECK (
        (source_kind = 'USER_ACTION' AND actor_user_id IS NOT NULL AND actor_user_id > 0 AND bootstrap_batch_id IS NULL)
        OR (source_kind = 'SYSTEM_BOOTSTRAP' AND actor_user_id IS NULL AND bootstrap_batch_id IS NOT NULL AND bootstrap_batch_id > 0));

ALTER TABLE employee_configuration_audit_event
    MODIFY actor_user_id BIGINT UNSIGNED NULL COMMENT '真实操作者；完整系统来源时为空',
    ADD source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER_ACTION' COMMENT '本动作来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '仅本次系统动作所属初始化批次',
    ADD KEY idx_bootstrap_source_10 (bootstrap_batch_id),
    ADD CONSTRAINT chk_bootstrap_actor_10 CHECK (
        (source_kind = 'USER_ACTION' AND actor_user_id IS NOT NULL AND actor_user_id > 0 AND bootstrap_batch_id IS NULL)
        OR (source_kind = 'SYSTEM_BOOTSTRAP' AND actor_user_id IS NULL AND bootstrap_batch_id IS NOT NULL AND bootstrap_batch_id > 0));

-- 原正数规则迁入按来源条件的 CHECK，仍保留所有非操作者不变量。
ALTER TABLE employee_release
    DROP CHECK chk_employee_release_positive,
    ADD CONSTRAINT chk_employee_release_positive CHECK (employee_id > 0 AND release_no > 0 AND source_draft_revision > 0 AND config_schema_version > 0 AND availability_revision > 0),
    ADD CONSTRAINT chk_bootstrap_initial_availability CHECK (availability_source_kind <> 'SYSTEM_BOOTSTRAP' OR (creation_source_kind = 'SYSTEM_BOOTSTRAP' AND availability_bootstrap_batch_id = bootstrap_batch_id AND availability_revision = 1 AND availability_status = 'AVAILABLE' AND availability_changed_at = published_at));
ALTER TABLE tool_binding_revision
    DROP CHECK chk_tool_binding_positive,
    ADD CONSTRAINT chk_tool_binding_positive CHECK (binding_schema_version > 0);
ALTER TABLE employee_use_grant
    DROP CHECK chk_employee_grant_positive,
    ADD CONSTRAINT chk_employee_grant_positive CHECK (user_id > 0 AND employee_id > 0 AND (revoked_by IS NULL OR revoked_by > 0));
ALTER TABLE employee_configuration_audit_event
    DROP CHECK chk_employee_config_audit_positive,
    DROP INDEX uk_actor_success_request,
    ADD receipt_scope_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '成功收据来源 USER_ACTION 或 SYSTEM_BOOTSTRAP',
    ADD receipt_scope_id BIGINT UNSIGNED NOT NULL COMMENT '人工动作取 actor；系统动作取 batch.id',
    ADD CONSTRAINT chk_employee_config_audit_positive CHECK (employee_id > 0 AND (draft_revision IS NULL OR draft_revision > 0) AND (employee_release_id IS NULL OR employee_release_id > 0)),
    ADD CONSTRAINT chk_bootstrap_receipt_scope CHECK (receipt_scope_kind = source_kind AND receipt_scope_id > 0 AND ((source_kind = 'USER_ACTION' AND actor_user_id IS NOT NULL AND receipt_scope_id = actor_user_id) OR (source_kind = 'SYSTEM_BOOTSTRAP' AND bootstrap_batch_id IS NOT NULL AND receipt_scope_id = bootstrap_batch_id))),
    ADD UNIQUE KEY uk_scope_success_request (receipt_scope_kind(24),receipt_scope_id,success_request_id(128));

-- 身份初始化的成功事件同样只允许一份原请求事实；不改变人工审计的既有去重语义。
ALTER TABLE identity_audit_event
    ADD bootstrap_success_request_id VARCHAR(128) GENERATED ALWAYS AS
        (CASE WHEN source_kind='SYSTEM_BOOTSTRAP' AND outcome='SUCCESS' THEN request_id ELSE NULL END) STORED COMMENT '仅系统成功请求参与批次去重',
    ADD CONSTRAINT chk_identity_bootstrap_request CHECK (source_kind<>'SYSTEM_BOOTSTRAP' OR (request_id IS NOT NULL AND CHAR_LENGTH(TRIM(request_id)) > 0)),
    ADD UNIQUE KEY uk_bootstrap_success_request (bootstrap_batch_id,bootstrap_success_request_id(128));

-- 系统修改来源只适用于初始状态。后续受控改密／停用／撤权等动作记录真实操作者。
ALTER TABLE platform_user
    ADD CONSTRAINT chk_bootstrap_initial_user CHECK (modification_source_kind<>'SYSTEM_BOOTSTRAP' OR (account_status='ACTIVE' AND phone_revision=1 AND security_version=1 AND authorization_version=1));
ALTER TABLE employee_draft
    ADD CONSTRAINT chk_bootstrap_initial_draft CHECK (save_source_kind<>'SYSTEM_BOOTSTRAP' OR draft_revision=1);

-- 没有操作者列的预置事实仍保留创建批次；非系统事实可空，由所属主记录追溯。
ALTER TABLE user_credential
    ADD bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '系统预置创建批次；非系统创建为空',
    ADD KEY idx_bootstrap_batch (bootstrap_batch_id),
    ADD CONSTRAINT chk_user_credential_batch CHECK (bootstrap_batch_id IS NULL OR bootstrap_batch_id > 0);
ALTER TABLE digital_employee
    ADD bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '系统预置创建批次；非系统创建为空',
    ADD KEY idx_bootstrap_batch (bootstrap_batch_id),
    ADD CONSTRAINT chk_digital_employee_batch CHECK (bootstrap_batch_id IS NULL OR bootstrap_batch_id > 0);
ALTER TABLE employee_release_skill
    ADD bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '系统预置创建批次；非系统创建为空',
    ADD KEY idx_bootstrap_batch (bootstrap_batch_id),
    ADD CONSTRAINT chk_employee_release_skill_batch CHECK (bootstrap_batch_id IS NULL OR bootstrap_batch_id > 0);
ALTER TABLE employee_release_skill_resource
    ADD bootstrap_batch_id BIGINT UNSIGNED NULL COMMENT '系统预置创建批次；非系统创建为空',
    ADD KEY idx_bootstrap_batch (bootstrap_batch_id),
    ADD CONSTRAINT chk_employee_release_skill_resource_batch CHECK (bootstrap_batch_id IS NULL OR bootstrap_batch_id > 0);

