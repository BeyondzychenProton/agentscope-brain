-- 阶段 1：新建意图、消息素材暂存与普通提交永久封闭控制表。
-- 依据后端最小数据调整与初始化契约 §6.1／§7／§8；适用于 MySQL 8.0.16+。
-- 须在已执行 01～02（至少含 platform_user／business_session／business_message／message_media／business_run）的空库或合成探针库上追加。
-- 无物理外键、触发器、级联或存储过程；正式 HTTP、MinIO 与业务 Run 不在本 DDL 范围。
-- 所有时间由应用按 UTC 写入；不保存临时签名 URL、草稿原文或凭据。

CREATE TABLE session_intent (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '新建意图记录主键',
    owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '意图所有者稳定 UserId',
    client_intent_id BINARY(16) NOT NULL COMMENT '浏览器标签页生成的 clientIntentId，UUID 十六字节',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '冻结数字员工 ID',
    employee_release_id BIGINT UNSIGNED NOT NULL COMMENT '准备时冻结的发布版本 ID',
    image_capability_sha256 BINARY(32) NOT NULL COMMENT '有效图片能力快照规范摘要',
    image_capability_json JSON NOT NULL COMMENT '有效图片能力快照正文；不含凭据',
    prepared_at DATETIME(6) NOT NULL COMMENT '意图登记时间，UTC',
    bound_session_id BIGINT UNSIGNED NULL COMMENT '首次正式接纳绑定的唯一业务 SessionId',
    first_submission_id BIGINT UNSIGNED NULL COMMENT '完成绑定的普通提交控制行 ID',
    bound_at DATETIME(6) NULL COMMENT '绑定提交时间，UTC',
    prepare_invalid_reason VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '准备失效受控原因码；空表示仍可首接纳',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '并发控制版本',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_owner_client_intent (owner_user_id, client_intent_id),
    UNIQUE KEY uk_bound_session_id (bound_session_id),
    UNIQUE KEY uk_first_submission_id (first_submission_id),
    KEY idx_owner_employee_prepared (owner_user_id, employee_id, prepared_at),
    KEY idx_employee_release (employee_release_id),
    CONSTRAINT chk_session_intent_positive CHECK (
        owner_user_id > 0 AND employee_id > 0 AND employee_release_id > 0 AND row_version > 0
        AND (first_submission_id IS NULL OR first_submission_id > 0)
    ),
    CONSTRAINT chk_session_intent_capability CHECK (JSON_TYPE(image_capability_json) = 'OBJECT'),
    CONSTRAINT chk_session_intent_bind_pair CHECK (
        (bound_session_id IS NULL AND first_submission_id IS NULL AND bound_at IS NULL)
        OR (bound_session_id IS NOT NULL AND first_submission_id IS NOT NULL AND bound_at IS NOT NULL)
    ),
    CONSTRAINT chk_session_intent_reason CHECK (
        prepare_invalid_reason IS NULL OR CHAR_LENGTH(TRIM(prepare_invalid_reason)) > 0
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冻结新建意图及能力；至多绑定一个正式 Session';

CREATE TABLE media_upload (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '上传暂存记录主键',
    upload_id BINARY(16) NOT NULL COMMENT '稳定上传 UUID，对外标识',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '上传所有者稳定 UserId',
    upload_request_key BINARY(16) NOT NULL COMMENT '客户端上传准备幂等键 UUID',
    upload_request_sha256 BINARY(32) NOT NULL COMMENT '准备请求规范语义摘要',
    employee_id BIGINT UNSIGNED NOT NULL COMMENT '目标数字员工 ID',
    employee_release_id BIGINT UNSIGNED NOT NULL COMMENT '固定发布版本 ID',
    session_id BIGINT UNSIGNED NULL COMMENT '旧业务会话目标；与 session_intent_id 互斥',
    session_intent_id BIGINT UNSIGNED NULL COMMENT '新建意图目标；与 session_id 互斥',
    storage_profile VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '不可变私有对象存储配置版本引用',
    object_key VARCHAR(512) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务端分配且不复用的对象键',
    object_version VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '对象存储版本标识；未启用时为空',
    media_kind VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '一期仅 IMAGE',
    mime_type VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '可信验证后的 MIME；未验证为空',
    byte_count BIGINT UNSIGNED NULL COMMENT '可信字节数；未验证为空',
    pixel_width INT UNSIGNED NULL COMMENT '可信像素宽；未验证为空',
    pixel_height INT UNSIGNED NULL COMMENT '可信像素高；未验证为空',
    content_sha256 BINARY(32) NULL COMMENT '原件 SHA-256；未验证为空',
    original_filename VARCHAR(255) NULL COMMENT '展示文件名，不作存储路径',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'ISSUED／UPLOADING／VERIFYING／READY／RECONCILING／BOUND／BLOCKED／CLEANED',
    issued_at DATETIME(6) NOT NULL COMMENT '票据创建时间，UTC',
    expires_at DATETIME(6) NOT NULL COMMENT '固定 issued_at＋24h，不续期',
    validation_version INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '验证事实修订；未验证为 0',
    message_id BIGINT UNSIGNED NULL COMMENT 'BOUND 时正式消息 ID',
    message_media_id BIGINT UNSIGNED NULL COMMENT 'BOUND 时正式 message_media ID',
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'BLOCKED 等受控原因码',
    deletion_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE' COMMENT 'NONE／PENDING／CLAIMED／FAILED／DONE',
    deletion_fence BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '清理领取围栏',
    deletion_claimed_at DATETIME(6) NULL COMMENT '清理领取时间，UTC',
    deleted_at DATETIME(6) NULL COMMENT '对象删除确认时间，UTC',
    deletion_attempt_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '清理重试次数',
    object_presence_result VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '对象核对结果码；不保存临时 URL',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '并发控制版本',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_upload_id (upload_id),
    UNIQUE KEY uk_user_upload_request (user_id, upload_request_key),
    UNIQUE KEY uk_storage_profile_object_key (storage_profile(64), object_key(512)),
    UNIQUE KEY uk_message_media_id (message_media_id),
    KEY idx_owner_target_status (user_id, employee_id, status(16), expires_at),
    KEY idx_session_status (session_id, status(16)),
    KEY idx_session_intent_status (session_intent_id, status(16)),
    KEY idx_status_expires (status(16), expires_at),
    KEY idx_deletion_status (deletion_status(16), deletion_fence),
    KEY idx_bound_message (message_id),
    CONSTRAINT chk_media_upload_positive CHECK (
        user_id > 0 AND employee_id > 0 AND employee_release_id > 0 AND row_version > 0
        AND validation_version >= 0 AND deletion_attempt_count >= 0
        AND (session_id IS NULL OR session_id > 0)
        AND (session_intent_id IS NULL OR session_intent_id > 0)
        AND (message_id IS NULL OR message_id > 0)
        AND (message_media_id IS NULL OR message_media_id > 0)
    ),
    CONSTRAINT chk_media_upload_target CHECK (
        (session_id IS NOT NULL AND session_intent_id IS NULL)
        OR (session_id IS NULL AND session_intent_id IS NOT NULL)
    ),
    CONSTRAINT chk_media_upload_kind CHECK (media_kind = 'IMAGE'),
    CONSTRAINT chk_media_upload_locator CHECK (
        CHAR_LENGTH(TRIM(storage_profile)) > 0 AND CHAR_LENGTH(TRIM(object_key)) > 0
    ),
    CONSTRAINT chk_media_upload_status CHECK (
        status IN ('ISSUED', 'UPLOADING', 'VERIFYING', 'READY', 'RECONCILING', 'BOUND', 'BLOCKED', 'CLEANED')
    ),
    CONSTRAINT chk_media_upload_deletion CHECK (
        deletion_status IN ('NONE', 'PENDING', 'CLAIMED', 'FAILED', 'DONE')
    ),
    CONSTRAINT chk_media_upload_ttl CHECK (expires_at = issued_at + INTERVAL 24 HOUR),
    CONSTRAINT chk_media_upload_mime CHECK (mime_type IS NULL OR mime_type LIKE '%/%'),
    CONSTRAINT chk_media_upload_ready_facts CHECK (
        status NOT IN ('READY', 'BOUND')
        OR (
            mime_type IS NOT NULL AND byte_count IS NOT NULL AND byte_count > 0
            AND pixel_width IS NOT NULL AND pixel_width > 0
            AND pixel_height IS NOT NULL AND pixel_height > 0
            AND content_sha256 IS NOT NULL AND validation_version > 0
        )
    ),
    CONSTRAINT chk_media_upload_bound_pair CHECK (
        (status = 'BOUND' AND message_id IS NOT NULL AND message_media_id IS NOT NULL)
        OR (status <> 'BOUND' AND message_id IS NULL AND message_media_id IS NULL)
    ),
    CONSTRAINT chk_media_upload_blocked_reason CHECK (
        status <> 'BLOCKED' OR (reason_code IS NOT NULL AND CHAR_LENGTH(TRIM(reason_code)) > 0)
    ),
    CONSTRAINT chk_media_upload_deletion_pair CHECK (
        (deletion_status = 'NONE' AND deletion_claimed_at IS NULL AND deleted_at IS NULL)
        OR (deletion_status = 'PENDING' AND deletion_claimed_at IS NULL AND deleted_at IS NULL)
        OR (deletion_status = 'CLAIMED' AND deletion_claimed_at IS NOT NULL AND deleted_at IS NULL)
        OR (deletion_status = 'FAILED' AND deletion_claimed_at IS NOT NULL AND deleted_at IS NULL)
        OR (deletion_status = 'DONE' AND deleted_at IS NOT NULL)
    ),
    CONSTRAINT chk_media_upload_no_ready_after_delete_fail CHECK (
        NOT (deletion_status IN ('PENDING', 'CLAIMED', 'FAILED', 'DONE') AND status = 'READY')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='正式接纳前的上传暂存票据、定位、验证、绑定与清理事实';

CREATE TABLE ordinary_submission (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '普通提交控制行主键',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '提交者稳定 UserId',
    submission_key BINARY(16) NOT NULL COMMENT '永久提交键 UUID，不复用',
    has_registered_intent TINYINT UNSIGNED NOT NULL COMMENT '1=曾登记完整意图；0=未登记原键封闭墓碑',
    target_session_id BIGINT UNSIGNED NULL COMMENT '既有业务会话目标；与 session_intent_id 互斥',
    session_intent_id BIGINT UNSIGNED NULL COMMENT '新建意图目标；与 target_session_id 互斥',
    employee_id BIGINT UNSIGNED NULL COMMENT '完整意图的数字员工 ID',
    submission_sha256 BINARY(32) NULL COMMENT '固定提交语义摘要；墓碑为空',
    upload_ref_json JSON NULL COMMENT '有序上传 UUID 清单；纯文字为空数组；墓碑为空',
    status VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'REGISTERED／RECONCILING／ACCEPTED／CLOSED_UNACCEPTED',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '并发控制版本',
    acceptance_fence BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '接纳／封闭竞争围栏',
    accepted_session_id BIGINT UNSIGNED NULL COMMENT '成功接纳的 SessionId',
    accepted_message_id BIGINT UNSIGNED NULL COMMENT '成功接纳的正式消息 ID',
    accepted_run_id BIGINT UNSIGNED NULL COMMENT '成功接纳创建的 RunId',
    accepted_at DATETIME(6) NULL COMMENT '正式接纳时间，UTC',
    closed_at DATETIME(6) NULL COMMENT '明确封闭时间，UTC',
    reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '封闭或核对原因码',
    close_request_key BINARY(16) NULL COMMENT '原封闭请求键',
    close_request_sha256 BINARY(32) NULL COMMENT '封闭请求规范摘要',
    lookup_request_key BINARY(16) NULL COMMENT '原回查请求键',
    gmt_create DATETIME(6) NOT NULL COMMENT '记录创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '记录最后修改时间，UTC',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_submission_key (user_id, submission_key),
    UNIQUE KEY uk_accepted_message_id (accepted_message_id),
    UNIQUE KEY uk_accepted_run_id (accepted_run_id),
    KEY idx_user_status_modified (user_id, status(24), gmt_modified),
    KEY idx_accepted_session (accepted_session_id),
    KEY idx_session_intent (session_intent_id),
    KEY idx_target_session (target_session_id),
    KEY idx_reconciling (status(24), acceptance_fence),
    CONSTRAINT chk_ordinary_submission_positive CHECK (
        user_id > 0 AND row_version > 0 AND has_registered_intent IN (0, 1)
        AND (employee_id IS NULL OR employee_id > 0)
        AND (target_session_id IS NULL OR target_session_id > 0)
        AND (session_intent_id IS NULL OR session_intent_id > 0)
        AND (accepted_session_id IS NULL OR accepted_session_id > 0)
        AND (accepted_message_id IS NULL OR accepted_message_id > 0)
        AND (accepted_run_id IS NULL OR accepted_run_id > 0)
    ),
    CONSTRAINT chk_ordinary_submission_status CHECK (
        status IN ('REGISTERED', 'RECONCILING', 'ACCEPTED', 'CLOSED_UNACCEPTED')
    ),
    CONSTRAINT chk_ordinary_submission_tombstone CHECK (
        (has_registered_intent = 0 AND status = 'CLOSED_UNACCEPTED'
            AND target_session_id IS NULL AND session_intent_id IS NULL
            AND employee_id IS NULL AND submission_sha256 IS NULL AND upload_ref_json IS NULL
            AND accepted_session_id IS NULL AND accepted_message_id IS NULL AND accepted_run_id IS NULL
            AND accepted_at IS NULL AND closed_at IS NOT NULL
            AND reason_code IS NOT NULL AND CHAR_LENGTH(TRIM(reason_code)) > 0)
        OR has_registered_intent = 1
    ),
    CONSTRAINT chk_ordinary_submission_intent CHECK (
        has_registered_intent = 0
        OR (
            employee_id IS NOT NULL AND submission_sha256 IS NOT NULL
            AND upload_ref_json IS NOT NULL AND JSON_TYPE(upload_ref_json) = 'ARRAY'
            AND (
                (target_session_id IS NOT NULL AND session_intent_id IS NULL)
                OR (target_session_id IS NULL AND session_intent_id IS NOT NULL)
            )
        )
    ),
    CONSTRAINT chk_ordinary_submission_accepted CHECK (
        status <> 'ACCEPTED'
        OR (
            has_registered_intent = 1
            AND accepted_session_id IS NOT NULL
            AND accepted_message_id IS NOT NULL
            AND accepted_run_id IS NOT NULL
            AND accepted_at IS NOT NULL
            AND closed_at IS NULL
        )
    ),
    CONSTRAINT chk_ordinary_submission_closed_intent CHECK (
        NOT (has_registered_intent = 1 AND status = 'CLOSED_UNACCEPTED')
        OR (closed_at IS NOT NULL AND reason_code IS NOT NULL AND CHAR_LENGTH(TRIM(reason_code)) > 0
            AND accepted_session_id IS NULL AND accepted_message_id IS NULL AND accepted_run_id IS NULL
            AND accepted_at IS NULL)
    ),
    CONSTRAINT chk_ordinary_submission_open CHECK (
        status NOT IN ('REGISTERED', 'RECONCILING')
        OR (has_registered_intent = 1 AND closed_at IS NULL AND accepted_at IS NULL
            AND accepted_session_id IS NULL AND accepted_message_id IS NULL AND accepted_run_id IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='UserId＋submission_key 的普通提交意图、接纳结果或永久封闭墓碑';
