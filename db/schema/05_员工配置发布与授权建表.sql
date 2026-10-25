-- 一期员工配置、发布与授权；2026-10-22 依据用户确认规格生成。
-- 专用空库依次执行 01～05；不是既有数据迁移脚本。
-- 无物理外键、触发器、级联或存储过程；引用、不可变性、摘要与发布事务由应用及巡检维护。
-- 所有时间由应用按 UTC 写入；配置只保存凭证引用，不包含秘密值。

CREATE TABLE digital_employee (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录主键',
    employee_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '稳定唯一员工业务代码',
    display_name VARCHAR(100) NOT NULL COMMENT '员工展示名称',
    description VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '展示说明',
    current_release_id BIGINT UNSIGNED NULL COMMENT '新会话发布指针，须属于本员工；未发布时为空',
    last_release_no BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已分配的最大发布号，无版本时为零',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '员工元数据与指针并发修订',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_employee_code (employee_code(64)),
    CONSTRAINT chk_digital_employee_text CHECK (CHAR_LENGTH(TRIM(employee_code)) > 0 AND CHAR_LENGTH(TRIM(display_name)) > 0),
    CONSTRAINT chk_digital_employee_revision CHECK (row_version > 0 AND (current_release_id IS NULL OR current_release_id > 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='稳定数字员工身份与当前发布指针，无员工整体启停';

CREATE TABLE employee_draft (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录主键',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '所属数字员工 ID',
    draft_revision BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '每次成功保存增加的修订',
    config_schema_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '草稿结构与规范编码版本',
    config_json JSON NOT NULL COMMENT '完整草稿文本包，允许不完整，不能直接运行',
    dependency_sha256 BINARY(32) NULL COMMENT '保存时固定的有效依赖指纹；空表示未就绪',
    saved_by BIGINT UNSIGNED NULL COMMENT '最近保存的管理员；仅初始化草稿可空',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_employee_id (employee_id),
    CONSTRAINT chk_employee_draft_positive CHECK (employee_id > 0 AND draft_revision > 0 AND config_schema_version > 0 AND (saved_by IS NULL OR saved_by > 0)),
    CONSTRAINT chk_employee_draft_json CHECK (JSON_TYPE(config_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='唯一可变草稿及固定发布依赖，保存不影响正式服务';

CREATE TABLE employee_release (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录主键',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '所属数字员工 ID',
    release_no BIGINT UNSIGNED NOT NULL COMMENT '员工内从一开始的发布号',
    source_draft_revision BIGINT UNSIGNED NOT NULL COMMENT '真正创建本版的历史草稿修订',
    config_schema_version INT UNSIGNED NOT NULL COMMENT '发布结构与有效配置编码版本',
    config_json JSON NOT NULL COMMENT '包含真实 Skill 快照和绑定引用的发布清单',
    dependency_sha256 BINARY(32) NOT NULL COMMENT '创建本版时固定的有效依赖指纹',
    config_sha256 BINARY(32) NOT NULL COMMENT '有效内容摘要，排除生成 ID、修订、时间和可用性',
    manifest_sha256 BINARY(32) NOT NULL COMMENT '含真实引用的最终清单规范字节摘要',
    published_by BIGINT UNSIGNED NOT NULL COMMENT '发布管理员 UserId',
    published_at DATETIME(6) NOT NULL COMMENT '正式发布时间，UTC',
    availability_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE 可用或 DISABLED 停用',
    availability_revision BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '可用性变化时增加的并发修订',
    availability_changed_by BIGINT UNSIGNED NOT NULL COMMENT '最近可用性设置人，初值为发布人',
    availability_changed_at DATETIME(6) NOT NULL COMMENT '最近可用性设置时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_employee_release_no (employee_id, release_no),
    UNIQUE KEY uk_employee_source_draft_revision (employee_id, source_draft_revision),
    KEY idx_employee_config_sha256 (employee_id, config_sha256),
    KEY idx_employee_availability (employee_id, availability_status(16)),
    CONSTRAINT chk_employee_release_positive CHECK (employee_id > 0 AND release_no > 0 AND source_draft_revision > 0 AND config_schema_version > 0 AND published_by > 0 AND availability_revision > 0 AND availability_changed_by > 0),
    CONSTRAINT chk_employee_release_json CHECK (JSON_TYPE(config_json) = 'OBJECT'),
    CONSTRAINT chk_employee_release_availability CHECK (availability_status IN ('AVAILABLE', 'DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变发布配置；仅可用性变化，历史内容摘要不唯一';

CREATE TABLE tool_binding_revision (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录主键',
    tool_binding_version VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不改派的绑定版本，兼容既有工具操作',
    provider_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '受控服务提供方标识',
    tool_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '实际工具名称',
    binding_schema_version INT UNSIGNED NOT NULL COMMENT '绑定结构与摘要编码版本',
    binding_json JSON NOT NULL COMMENT '固定目标、契约、Adapter、身份和策略，仅存凭证引用',
    binding_sha256 BINARY(32) NOT NULL COMMENT '绑定内容规范摘要，不含秘密值或版本标识',
    created_by BIGINT UNSIGNED NOT NULL COMMENT '创建绑定的发布管理员 UserId',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_binding_version (tool_binding_version(64)),
    KEY idx_provider_tool_digest (provider_key(64), tool_name(128), binding_sha256),
    CONSTRAINT chk_tool_binding_text CHECK (CHAR_LENGTH(TRIM(tool_binding_version)) > 0 AND CHAR_LENGTH(TRIM(provider_key)) > 0 AND CHAR_LENGTH(TRIM(tool_name)) > 0),
    CONSTRAINT chk_tool_binding_positive CHECK (binding_schema_version > 0 AND created_by > 0),
    CONSTRAINT chk_tool_binding_json CHECK (JSON_TYPE(binding_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变工具调用语义，由发布清单直接选用';

CREATE TABLE employee_use_grant (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '被授权用户稳定 UserId',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '被授权数字员工 ID',
    grant_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ACTIVE 已授权或 REVOKED 已撤销',
    granted_by BIGINT UNSIGNED NOT NULL COMMENT '最近一次真实授予的管理员',
    granted_at DATETIME(6) NOT NULL COMMENT '最近授予或再授予时间，UTC',
    revoked_by BIGINT UNSIGNED NULL COMMENT '当前撤销管理员；再授予后清空',
    revoked_at DATETIME(6) NULL COMMENT '当前撤销时间，UTC；再授予后清空',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_employee (user_id, employee_id),
    KEY idx_employee_grant_status (employee_id, grant_status(16)),
    CONSTRAINT chk_employee_grant_positive CHECK (user_id > 0 AND employee_id > 0 AND granted_by > 0 AND (revoked_by IS NULL OR revoked_by > 0)),
    CONSTRAINT chk_employee_grant_status CHECK (grant_status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT chk_employee_grant_revocation CHECK ((grant_status = 'ACTIVE' AND revoked_by IS NULL AND revoked_at IS NULL) OR (grant_status = 'REVOKED' AND revoked_by IS NOT NULL AND revoked_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户乘员工的当前授权关系，历史复用身份审计';

CREATE TABLE employee_configuration_audit_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录主键',
    actor_user_id BIGINT UNSIGNED NOT NULL COMMENT '可信固定管理员 UserId',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '已定位的目标员工 ID',
    action_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'DRAFT_SAVE、EMPLOYEE_PUBLISH、RELEASE_DISABLE 或 RELEASE_ENABLE',
    outcome CHAR(7) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'SUCCESS 或 FAILURE',
    result_code VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '成功结果：SAVED、CREATED、NO_CHANGE、DISABLED、ENABLED',
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '失败受控原因，不含请求或配置原文',
    draft_revision BIGINT UNSIGNED NULL COMMENT '保存结果修订或发布请求修订，非结果版本来源修订',
    employee_release_id BIGINT UNSIGNED NULL COMMENT '发布返回版本或可用性目标版本',
    request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '管理员范围内稳定管理请求键',
    request_sha256 BINARY(32) NOT NULL COMMENT '操作者、目标、动作、期望修订及输入的稳定指纹',
    success_request_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (CASE WHEN outcome = 'SUCCESS' THEN request_id ELSE NULL END) STORED COMMENT '仅成功请求参与唯一性，失败允许重复',
    change_summary JSON NULL COMMENT '受控字段与修订的脱敏摘要，不含正文或秘密值',
    occurred_at DATETIME(6) NOT NULL COMMENT '事件时间，UTC',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_actor_success_request (actor_user_id, success_request_id(128)),
    KEY idx_employee_occurred_at (employee_id, occurred_at),
    KEY idx_employee_release_id (employee_release_id),
    KEY idx_request_id (request_id(128)),
    CONSTRAINT chk_employee_config_audit_positive CHECK (actor_user_id > 0 AND employee_id > 0 AND (draft_revision IS NULL OR draft_revision > 0) AND (employee_release_id IS NULL OR employee_release_id > 0)),
    CONSTRAINT chk_employee_config_audit_action CHECK (action_code IN ('DRAFT_SAVE', 'EMPLOYEE_PUBLISH', 'RELEASE_DISABLE', 'RELEASE_ENABLE')),
    CONSTRAINT chk_employee_config_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE')),
    CONSTRAINT chk_employee_config_audit_result CHECK ((outcome = 'SUCCESS' AND result_code IS NOT NULL AND result_code IN ('SAVED', 'CREATED', 'NO_CHANGE', 'DISABLED', 'ENABLED') AND reason_code IS NULL) OR (outcome = 'FAILURE' AND result_code IS NULL AND reason_code IS NOT NULL AND CHAR_LENGTH(TRIM(reason_code)) > 0)),
    CONSTRAINT chk_employee_config_audit_request CHECK (CHAR_LENGTH(TRIM(request_id)) > 0),
    CONSTRAINT chk_employee_config_audit_summary CHECK (change_summary IS NULL OR JSON_TYPE(change_summary) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='追加配置审计与成功收据，授权变化仍由身份审计记录';

CREATE TABLE employee_release_skill (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录主键',
    employee_release_id BIGINT UNSIGNED NOT NULL COMMENT '所属员工发布版本 ID',
    skill_name VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '从完整入口原文解析的 Skill 名称',
    description VARCHAR(2048) NOT NULL COMMENT '与入口同次解析的完整描述',
    metadata_json JSON NOT NULL COMMENT '完整可表达 metadata，原始 frontmatter 保留在入口',
    skill_markdown LONGTEXT NOT NULL COMMENT '完整 SKILL.md，含 frontmatter 和正文',
    package_sha256 BINARY(32) NOT NULL COMMENT '入口、metadata 及全部文本资源的完整包摘要',
    resource_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '实际文本资源数量，零合法',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_release_skill_name (employee_release_id, skill_name(64)),
    CONSTRAINT chk_release_skill_positive CHECK (employee_release_id > 0),
    CONSTRAINT chk_release_skill_text CHECK (CHAR_LENGTH(TRIM(skill_name)) > 0 AND CHAR_LENGTH(TRIM(description)) > 0 AND CHAR_LENGTH(TRIM(skill_markdown)) > 0),
    CONSTRAINT chk_release_skill_metadata CHECK (JSON_TYPE(metadata_json) = 'OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='按员工发布固定的完整 Skill 主体';

CREATE TABLE employee_release_skill_resource (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '记录主键',
    release_skill_id BIGINT UNSIGNED NOT NULL COMMENT '所属正式 Skill 快照 ID',
    resource_path VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '中文相对路径，等价路径唯一；合法性由应用检查',
    resource_content LONGTEXT NOT NULL COMMENT '实际 UTF-8 文本，空文本合法',
    content_sha256 BINARY(32) NOT NULL COMMENT '实际文本字节的 SHA-256',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_skill_resource_path (release_skill_id, resource_path(255)),
    CONSTRAINT chk_release_resource_positive CHECK (release_skill_id > 0),
    CONSTRAINT chk_release_resource_path CHECK (CHAR_LENGTH(TRIM(resource_path)) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='不可变 Skill 文本资源，不跨版本读取最新内容';
