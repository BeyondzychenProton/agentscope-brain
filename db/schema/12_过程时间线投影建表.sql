-- 阶段 2 / 12，#41；依据过程时间线规格：独立 process 流、步骤头与有界详情。
-- 空库依次执行 01～05、08、09、10、11、本文件；不是存量迁移或应用写入入口。
-- 无外键、触发器或存储过程。跨行不变量由受控短事务与后续巡检验证。
-- 精确 UTF-8 字节存 BLOB；UTC DATETIME(6)；过程流与会话投影流隔离。

ALTER TABLE business_session
    ADD COLUMN process_projection_stream_id BINARY(16) NULL COMMENT '服务端生成的过程投影流 UUID；未建立过程投影时允许为空',
    ADD COLUMN last_process_projection_seq BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '持 Session 锁与业务同事务分配的过程流最后已提交序号；0 为初始水位',
    ADD CONSTRAINT chk_session_process_projection_head CHECK (process_projection_stream_id IS NOT NULL OR last_process_projection_seq=0);

-- MySQL 8.0.19+：DROP CHECK；更早版本需手工改写为 DROP CONSTRAINT 或重建表。
ALTER TABLE session_projection_event
    DROP CHECK chk_projection_event_protocol,
    MODIFY COLUMN protocol_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'web-projection-v1 或 web-projection-process-v1；按流隔离，不混读',
    ADD CONSTRAINT chk_projection_event_protocol CHECK (protocol_version IN ('web-projection-v1','web-projection-process-v1'));

CREATE TABLE projection_process_step (
    step_id BINARY(16) NOT NULL COMMENT '服务端生成的稳定过程步骤 UUID',
    owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '所属稳定平台用户 ID',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 SessionId',
    run_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 RunId；不将过程提升为正式历史',
    stream_id BINARY(16) NOT NULL COMMENT '所属过程投影流 UUID',
    step_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'STAGE／TOOL_STEP／REASONING',
    step_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RUNNING／DONE／FAILED／WAITING／CANCELLED／INTERRUPTED／UNKNOWN',
    title VARCHAR(200) NOT NULL COMMENT '受控中文短标题',
    summary VARCHAR(500) NULL COMMENT '受控中文短摘要；可空',
    first_seq BIGINT UNSIGNED NOT NULL COMMENT '步骤首次出现的过程批次序号，永久固定',
    first_position SMALLINT UNSIGNED NOT NULL COMMENT '首次批内位置，从 1 开始，永久固定',
    view_revision BIGINT UNSIGNED NOT NULL COMMENT '展示修订号，从 1 开始；更新不改首次锚点',
    collapsed_by_default TINYINT(1) NOT NULL COMMENT '默认是否折叠；REASONING 通常为 1',
    operation_id BIGINT UNSIGNED NULL COMMENT 'TOOL_STEP 与工具账本关联；纯 STAGE 可空',
    current_detail_version BIGINT UNSIGNED NULL COMMENT '当前详情不可变版本；无详情时为空',
    gmt_create DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '头指针修改时间，UTC',
    PRIMARY KEY (step_id),
    UNIQUE KEY uk_process_step_anchor (session_id,stream_id,first_seq,first_position),
    KEY idx_process_step_run (run_id),
    KEY idx_process_step_owner (owner_user_id,session_id),
    CONSTRAINT chk_process_step_positive CHECK (owner_user_id>0 AND session_id>0 AND run_id>0 AND first_seq>0
        AND first_position BETWEEN 1 AND 128 AND view_revision>0
        AND (operation_id IS NULL OR operation_id>0)
        AND (current_detail_version IS NULL OR current_detail_version>0)
        AND collapsed_by_default IN (0,1)),
    CONSTRAINT chk_process_step_kind CHECK (step_kind IN ('STAGE','TOOL_STEP','REASONING')),
    CONSTRAINT chk_process_step_status CHECK (step_status IN ('RUNNING','DONE','FAILED','WAITING','CANCELLED','INTERRUPTED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='过程时间线步骤头：稳定身份、不可变首次锚点与当前展示修订';

CREATE TABLE projection_process_step_detail (
    step_id BINARY(16) NOT NULL COMMENT '稳定过程步骤 UUID，逻辑引用步骤头',
    detail_version BIGINT UNSIGNED NOT NULL COMMENT '不可变详情版本号，从 1 开始',
    body MEDIUMBLOB NULL COMMENT '有界内联 UTF-8 详情；本切片仅 INLINE；FIXED_REF 后续切片',
    byte_count INT UNSIGNED NOT NULL COMMENT '详情实际 UTF-8 字节数；INLINE 上限 8 KiB',
    body_sha256 BINARY(32) NOT NULL COMMENT '详情精确 UTF-8 字节 SHA-256',
    payload_storage VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INLINE；FIXED_REF 后续切片',
    projection_seq BIGINT UNSIGNED NOT NULL COMMENT '产生该详情版本的过程批次序号',
    gmt_create DATETIME(6) NOT NULL COMMENT '不可变详情创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '创建时固定，UTC；不授权原地更新',
    PRIMARY KEY (step_id,detail_version),
    CONSTRAINT chk_process_step_detail_positive CHECK (detail_version>0 AND projection_seq>0),
    CONSTRAINT chk_process_step_detail_storage CHECK (payload_storage='INLINE'),
    CONSTRAINT chk_process_step_detail_bytes CHECK (byte_count>0 AND byte_count<=8192
        AND body IS NOT NULL AND byte_count=OCTET_LENGTH(body))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='过程步骤有界详情不可变版本；本切片仅 8 KiB INLINE';
