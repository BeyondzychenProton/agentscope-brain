-- 一期用户与管理员身份基线；适用于 MySQL 8.0。
-- 遵循阿里巴巴《Java 开发手册》MySQL 规约：单数小写表名、每表 id/gmt_create/gmt_modified、
-- 是/否字段 is_xxx + TINYINT UNSIGNED、业务唯一索引 uk_、普通索引 idx_、无外键与存储过程。
-- MySQL 主键索引的物理名称固定为 PRIMARY，不能改名为规约文字中的 pk_id。
-- 先选择专用空 schema 再执行一次；所有时间由应用按 UTC 写入，不自动切换数据库。

-- 平台账号：id 是稳定 UserId；手机号由号码登记表提供，姓名仅用于展示。
CREATE TABLE platform_user (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '稳定且不复用的平台 UserId；对外标识由服务层封装',
    display_name VARCHAR(100) NOT NULL COMMENT '员工展示姓名，允许重名和更正',
    account_status CHAR(8) NOT NULL DEFAULT 'ACTIVE' COMMENT '平台账号状态：ACTIVE 或 DISABLED，定长状态码',
    phone_revision BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '当前登录手机号的变更版本',
    security_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '凭据或账号安全状态变更时提升的会话版本',
    authorization_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '数字员工授权变更时提升的授权版本',
    created_by_user_id BIGINT UNSIGNED NULL COMMENT '创建人 UserId；运维初始化时为空',
    modified_by_user_id BIGINT UNSIGNED NULL COMMENT '最后修改人 UserId；运维初始化时为空',
    status_changed_at DATETIME(6) NOT NULL COMMENT '账号状态最近生效时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    CONSTRAINT chk_platform_user_status CHECK (account_status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT chk_platform_user_versions CHECK (phone_revision > 0 AND security_version > 0 AND authorization_version > 0),
    CONSTRAINT chk_platform_user_name CHECK (CHAR_LENGTH(TRIM(display_name)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台用户；管理员与普通员工共用账号';

-- 密码摘要与用户一对一。无外键，开户和巡检须核对 user_id 所指用户。
CREATE TABLE user_credential (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '凭据记录主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属平台 UserId，由应用层维护引用完整性',
    password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可逆慢哈希编码，不得保存明文或可逆密码',
    is_password_change_required TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '是否必须先改密：1 是，0 否',
    credential_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '密码凭据变更版本',
    password_changed_at DATETIME(6) NULL COMMENT '用户最近完成改密的时间，UTC；初始密码时为空',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id),
    CONSTRAINT chk_user_credential_change_required CHECK (is_password_change_required IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台用户密码凭据和首次改密状态';

-- 固定管理员使用同一平台账号。唯一单例键保证最多一人，初始化与启动检查保证恰有一人。
CREATE TABLE fixed_admin_assignment (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '固定管理员绑定记录主键',
    singleton_key TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '固定单例键，只允许数值 1',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '固定管理员的 UserId，由应用层核对有效账号',
    initialization_ref VARCHAR(128) NOT NULL COMMENT '受控部署或运维初始化记录编号',
    initialized_at DATETIME(6) NOT NULL COMMENT '固定管理员初始化时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_singleton_key (singleton_key),
    UNIQUE KEY uk_user_id (user_id),
    CONSTRAINT chk_fixed_admin_singleton CHECK (singleton_key = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='唯一固定管理员与平台用户的绑定';

-- 手机号全局登记。HELD 号码保留给原用户，跨用户转交须受控核对和审计。
-- CURRENT 时生成列取 owner_user_id；HELD 时为 NULL，唯一索引限制每名用户最多一个当前号码。
CREATE TABLE phone_reservation (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '手机号登记记录主键',
    phone_normalized VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化后的完整登录手机号；具体格式待接入联调确认',
    owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '当前或历史保留的归属平台 UserId',
    reservation_status VARCHAR(16) NOT NULL COMMENT '号码状态：CURRENT 当前登录号，HELD 历史保留号',
    current_owner_user_id BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN reservation_status = 'CURRENT' THEN owner_user_id ELSE NULL END) STORED COMMENT '当前号码的用户唯一索引生成列',
    ownership_source_ref VARCHAR(128) NOT NULL COMMENT '可信员工名册或受控核对记录编号，不存名册原文',
    ownership_checked_by BIGINT UNSIGNED NULL COMMENT '归属核对人 UserId；管理员初始化时可为空',
    ownership_checked_at DATETIME(6) NOT NULL COMMENT '手机号归属核对时间，UTC',
    reserved_at DATETIME(6) NOT NULL COMMENT '号码首次登记时间，UTC',
    held_at DATETIME(6) NULL COMMENT '号码转为历史保留状态的时间，UTC',
    transferred_at DATETIME(6) NULL COMMENT '受控跨用户转交时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_phone_normalized (phone_normalized(32)),
    UNIQUE KEY uk_current_owner_user_id (current_owner_user_id),
    KEY idx_owner_user_id_reservation_status (owner_user_id, reservation_status(16)),
    CONSTRAINT chk_phone_status CHECK (reservation_status IN ('CURRENT', 'HELD')),
    CONSTRAINT chk_phone_status_time CHECK ((reservation_status = 'CURRENT' AND ISNULL(held_at)) OR (reservation_status = 'HELD' AND NOT ISNULL(held_at))),
    CONSTRAINT chk_phone_nonblank CHECK (CHAR_LENGTH(TRIM(phone_normalized)) BETWEEN 6 AND 32)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='登录手机号当前归属与历史保留';

-- 会议探测只表示某次对指定号码版本的观察，不证明本人归属或永久工具权限。
CREATE TABLE meeting_identity_check_state (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会议账号检查状态记录主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '所属平台 UserId；应用层核对账号与号码归属',
    checked_phone_id BIGINT UNSIGNED NULL COMMENT '本次检查使用的手机号登记记录 ID',
    checked_phone_revision BIGINT UNSIGNED NULL COMMENT '本次检查对应的用户手机号版本',
    probe_status VARCHAR(32) NOT NULL DEFAULT 'UNCHECKED' COMMENT 'UNCHECKED、PASSED、AUTH_OR_IDENTITY_REJECTED、UNAVAILABLE 或 ERROR',
    probed_at DATETIME(6) NULL COMMENT '最近一次检查完成时间，UTC',
    last_http_status SMALLINT UNSIGNED NULL COMMENT '最近一次 MCP HTTP 状态码；网络失败时可为空',
    version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '检查状态更新版本',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_id (user_id),
    CONSTRAINT chk_meeting_check_status CHECK (probe_status IN ('UNCHECKED', 'PASSED', 'AUTH_OR_IDENTITY_REJECTED', 'UNAVAILABLE', 'ERROR')),
    CONSTRAINT chk_meeting_check_passed CHECK (probe_status <> 'PASSED' OR (NOT ISNULL(checked_phone_id) AND NOT ISNULL(checked_phone_revision) AND NOT ISNULL(probed_at) AND NOT ISNULL(last_http_status) AND last_http_status BETWEEN 200 AND 299))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='当前手机号版本的会议账号可用性检查状态';

-- 探测尝试是追加事实；user_id、phone_id 的历史关联由服务层写入校验与巡检维护。
CREATE TABLE meeting_identity_check_attempt (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会议账号检查尝试记录主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '发起检查的平台 UserId',
    phone_id BIGINT UNSIGNED NOT NULL COMMENT '发起检查时使用的手机号登记记录 ID',
    phone_revision BIGINT UNSIGNED NOT NULL COMMENT '发起检查时的手机号版本',
    attempt_kind VARCHAR(24) NOT NULL COMMENT 'PROBE 工具列表探测或 TOOL_AUTH_FAILURE 工具身份失败',
    result_code VARCHAR(40) NOT NULL COMMENT '平台归一化结果码，不据此单独推断手机号无效',
    http_status SMALLINT UNSIGNED NULL COMMENT 'MCP HTTP 状态码；网络失败时可为空',
    provider_request_id VARCHAR(128) NULL COMMENT '上游请求标识；接口不提供时为空',
    observed_at DATETIME(6) NOT NULL COMMENT '结果观察时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC；追加记录通常等于创建时间',
    PRIMARY KEY (id),
    KEY idx_user_id_observed_at (user_id, observed_at),
    KEY idx_phone_id (phone_id),
    CONSTRAINT chk_meeting_attempt_kind CHECK (attempt_kind IN ('PROBE', 'TOOL_AUTH_FAILURE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会议账号探测和工具身份失败的追加记录';

-- 审计记录仅保存结构化事实；追加式权限和关联完整性由服务层及数据库账号权限保证。
CREATE TABLE identity_audit_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '身份审计事件主键',
    actor_user_id BIGINT UNSIGNED NULL COMMENT '执行人 UserId；受控部署初始化时为空',
    target_user_id BIGINT UNSIGNED NOT NULL COMMENT '被操作的平台 UserId',
    action_code VARCHAR(64) NOT NULL COMMENT '账号开通、改密、停用、启用、改号或授权等结构化动作码',
    outcome CHAR(7) NOT NULL COMMENT '执行结果：SUCCESS 或 FAILURE，定长状态码',
    reason_code VARCHAR(64) NULL COMMENT '受控原因分类码，不含自由文本敏感信息',
    old_phone_id BIGINT UNSIGNED NULL COMMENT '变更前手机号登记记录 ID',
    new_phone_id BIGINT UNSIGNED NULL COMMENT '变更后手机号登记记录 ID',
    old_owner_user_id BIGINT UNSIGNED NULL COMMENT '跨用户转交时的原号码归属 UserId',
    new_owner_user_id BIGINT UNSIGNED NULL COMMENT '跨用户转交时的新号码归属 UserId',
    employee_id BIGINT UNSIGNED NULL COMMENT '授权目标员工 ID；其他身份动作可空',
    grant_id BIGINT UNSIGNED NULL COMMENT '已存在的员工授权关系 ID；无关系时可空',
    authorization_version_before BIGINT UNSIGNED NULL COMMENT '授权动作观察或变更前的用户级授权版本',
    authorization_version_after BIGINT UNSIGNED NULL COMMENT '授权动作变更后版本；无变化时等于前值',
    request_id VARCHAR(128) NULL COMMENT '平台请求关联标识',
    occurred_at DATETIME(6) NOT NULL COMMENT '事件发生时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC；追加记录通常等于创建时间',
    PRIMARY KEY (id),
    KEY idx_target_user_id_occurred_at (target_user_id, occurred_at),
    KEY idx_actor_user_id_occurred_at (actor_user_id, occurred_at),
    KEY idx_request_id (request_id(128)),
    KEY idx_target_employee_occurred (target_user_id, employee_id, occurred_at),
    KEY idx_grant_id (grant_id),
    CONSTRAINT chk_identity_audit_revision_pair CHECK ((authorization_version_before IS NULL AND authorization_version_after IS NULL) OR (authorization_version_before IS NOT NULL AND authorization_version_after IS NOT NULL)),
    CONSTRAINT chk_identity_audit_grant_employee CHECK ((grant_id IS NULL AND authorization_version_before IS NULL AND authorization_version_after IS NULL) OR employee_id IS NOT NULL),
    CONSTRAINT chk_identity_audit_grant_positive CHECK ((employee_id IS NULL OR employee_id > 0) AND (grant_id IS NULL OR grant_id > 0) AND (authorization_version_before IS NULL OR authorization_version_before > 0) AND (authorization_version_after IS NULL OR authorization_version_after > 0)),
    CONSTRAINT chk_identity_audit_revision_order CHECK (authorization_version_before IS NULL OR authorization_version_after >= authorization_version_before),
    CONSTRAINT chk_identity_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台身份和授权变更的结构化审计事件';
