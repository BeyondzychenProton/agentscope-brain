-- 一期会话运行资料索引与快照引用（Issue #9 评审稿）；适用于 MySQL 8.0.16+。
-- 先在同一专用空 schema 中依次执行 01、02、03 号建表文件。
-- 资料文本只存于框架 JDBC KV，文件原件只存于私有对象存储；本文件的表只保存归属、定位、修订和摘要，不复制全文，
-- 所以不含 TEXT、BLOB、JSON 列。KV 的 namespace 由（员工，用户，Session）按设计文档 2.2 节推出，不在此存储。
-- 约定同前：每表 id/gmt_create/gmt_modified、状态码使用 ascii_bin、无物理外键与存储过程、业务时间由应用按 UTC 写入。
-- 这是待评审建表方案；DDL 不能证明框架 KV 往返、对象存储完整性或归属校验已经实现。

-- 每个 Session 内每个路径至多一个 ACTIVE 条目；内容变化就地递增 content_revision，不保留历史修订。
-- 被快照或正式消息引用的内容由引用方自带不可变定位，所以本表不需要历史行。删除用 DELETED 墓碑，行与 ID 保留以便追溯。
CREATE TABLE workspace_entry (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话运行资料条目主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '资料所有者 UserId，须与 Session 所有者一致，由写入校验',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '数字员工 ID，须与 business_session.employee_id 一致，由写入校验',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 SessionId，逻辑引用 business_session.id',
    source_run_id BIGINT UNSIGNED NOT NULL COMMENT '写入当前内容的来源 RunId，逻辑引用 business_run.id',
    employee_release_id BIGINT UNSIGNED NOT NULL COMMENT '写入时 Run 固定的数字员工发布版本 ID',
    entry_path VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'Session 内资料逻辑路径；排序规则与框架 KV 主键一致，大小写不同视为同一路径；合法性由应用校验',
    entry_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '用途：PLAN、TASK、NOTE、GENERATED 或 OTHER',
    storage_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'KV_TEXT 文本在框架 KV，OBJECT_FILE 文件原件在私有对象存储',
    entry_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 或 DELETED 墓碑',
    active_entry_path VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci GENERATED ALWAYS AS
        (CASE WHEN entry_status = 'ACTIVE' THEN entry_path ELSE NULL END) STORED COMMENT '同一 Session 有效路径的唯一键；删除后释放，可重新创建',
    content_revision BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '该条目内容修订；就地递增，条件更新以它为准',
    kv_version BIGINT UNSIGNED NULL COMMENT 'KV_TEXT：最近观察到的框架 KV 记录版本，仅供核对，不参与条件更新；OBJECT_FILE 为空',
    storage_profile VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'OBJECT_FILE：不可变的私有对象存储配置版本引用，不存凭据或临时 URL；KV_TEXT 为空',
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'OBJECT_FILE：服务端生成的不复用对象键；KV_TEXT 为空',
    object_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'OBJECT_FILE：对象存储的版本标识；未启用版本时为空',
    mime_type VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '经内容验证的 MIME 类型，不是客户端自报值',
    byte_count BIGINT UNSIGNED NOT NULL COMMENT '当前内容字节数；允许零字节',
    content_sha256 BINARY(32) NOT NULL COMMENT '当前内容 SHA-256，不以对象存储 ETag 代替',
    deleted_at DATETIME(6) NULL COMMENT '进入 DELETED 的时间，UTC；ACTIVE 为空',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_active_entry_path (session_id, active_entry_path(255)),
    UNIQUE KEY uk_storage_profile_object_key (storage_profile(64), object_key(512)),
    KEY idx_user_employee_session (user_id, employee_id, session_id),
    KEY idx_source_run_id (source_run_id),
    CONSTRAINT chk_workspace_entry_path CHECK (CHAR_LENGTH(TRIM(entry_path)) > 0),
    CONSTRAINT chk_workspace_entry_kind CHECK (entry_kind IN ('PLAN', 'TASK', 'NOTE', 'GENERATED', 'OTHER')),
    CONSTRAINT chk_workspace_entry_storage_type CHECK (storage_type IN ('KV_TEXT', 'OBJECT_FILE')),
    CONSTRAINT chk_workspace_entry_status CHECK (entry_status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT chk_workspace_entry_revision CHECK (content_revision > 0),
    CONSTRAINT chk_workspace_entry_mime CHECK (mime_type LIKE '%/%'),
    CONSTRAINT chk_workspace_entry_deleted CHECK ((entry_status = 'ACTIVE' AND deleted_at IS NULL) OR (entry_status = 'DELETED' AND deleted_at IS NOT NULL)),
    CONSTRAINT chk_workspace_entry_storage CHECK (
        (storage_type = 'KV_TEXT' AND kv_version IS NOT NULL AND storage_profile IS NULL AND object_key IS NULL AND object_version IS NULL)
        OR (storage_type = 'OBJECT_FILE' AND kv_version IS NULL AND storage_profile IS NOT NULL AND object_key IS NOT NULL
            AND CHAR_LENGTH(TRIM(storage_profile)) > 0 AND CHAR_LENGTH(TRIM(object_key)) > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话运行资料的归属、定位、修订与完整性索引，不保存全文';

-- 跨会话显式引用资料时固定当时内容的快照记录。一期没有触发入口，本表预期为空；快照不读取来源“最新版本”。
-- KV_TEXT 来源：内容复制到目标 Session namespace 的 snapshots/{id} 键；OBJECT_FILE 来源：对象不可覆盖，只复制定位。
CREATE TABLE workspace_snapshot_reference (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '资料快照引用主键，同时作为目标 KV 快照键 snapshots/{id} 的组成部分',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '来源与目标共同的所有者 UserId，由写入校验',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '来源与目标共同的数字员工 ID，由写入校验',
    source_entry_id BIGINT UNSIGNED NOT NULL COMMENT '来源 workspace_entry.id，逻辑引用',
    source_content_revision BIGINT UNSIGNED NOT NULL COMMENT '被固定的来源内容修订',
    source_content_sha256 BINARY(32) NOT NULL COMMENT '被固定的来源内容 SHA-256',
    source_session_id BIGINT UNSIGNED NOT NULL COMMENT '来源业务 SessionId',
    source_run_id BIGINT UNSIGNED NOT NULL COMMENT '写入被固定修订的来源 RunId',
    target_session_id BIGINT UNSIGNED NOT NULL COMMENT '目标业务 SessionId，快照保存在该 Session 的范围内',
    storage_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'KV_TEXT 或 OBJECT_FILE，与被固定来源一致',
    snapshot_kv_version BIGINT UNSIGNED NULL COMMENT 'KV_TEXT：目标快照写入后读回的 KV 版本，仅供核对；OBJECT_FILE 为空',
    storage_profile VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'OBJECT_FILE：引用的私有对象存储配置版本；KV_TEXT 为空',
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'OBJECT_FILE：引用的不可覆盖对象键；KV_TEXT 为空',
    object_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'OBJECT_FILE：来源固定的对象版本标识；未启用版本时为空',
    mime_type VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '被固定内容的 MIME 类型',
    byte_count BIGINT UNSIGNED NOT NULL COMMENT '被固定内容字节数',
    snapshot_sha256 BINARY(32) NOT NULL COMMENT '目标快照写入后读回计算的 SHA-256，必须等于 source_content_sha256',
    created_by_user_id BIGINT UNSIGNED NOT NULL COMMENT '发起引用的 UserId',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_target_session_source_revision (target_session_id, source_entry_id, source_content_revision),
    KEY idx_source_entry_id (source_entry_id),
    KEY idx_user_employee_target_session (user_id, employee_id, target_session_id),
    CONSTRAINT chk_workspace_snapshot_revision CHECK (source_content_revision > 0),
    CONSTRAINT chk_workspace_snapshot_storage_type CHECK (storage_type IN ('KV_TEXT', 'OBJECT_FILE')),
    CONSTRAINT chk_workspace_snapshot_mime CHECK (mime_type LIKE '%/%'),
    CONSTRAINT chk_workspace_snapshot_digest CHECK (snapshot_sha256 = source_content_sha256),
    CONSTRAINT chk_workspace_snapshot_storage CHECK (
        (storage_type = 'KV_TEXT' AND snapshot_kv_version IS NOT NULL AND storage_profile IS NULL AND object_key IS NULL AND object_version IS NULL)
        OR (storage_type = 'OBJECT_FILE' AND snapshot_kv_version IS NULL AND storage_profile IS NOT NULL AND object_key IS NOT NULL
            AND CHAR_LENGTH(TRIM(storage_profile)) > 0 AND CHAR_LENGTH(TRIM(object_key)) > 0)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='跨会话显式引用资料时固定的内容快照引用，一期无写入入口';
