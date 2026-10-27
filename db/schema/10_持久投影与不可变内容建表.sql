-- 阶段 1 / 10，#33；依据 #22 数据契约 §10/§13、#20 §3/§4/§8/§9。
-- 空库依次执行 01～05、08、09、本文件；不是存量迁移或应用写入入口。
-- 无外键、触发器或存储过程。跨行不变量由 29 号巡检及受控短事务探针验证。
-- 精确 UTF-8 字节存 BLOB；摘要不以 MySQL JSON 重表示计算。

ALTER TABLE business_session
    ADD COLUMN projection_stream_id BINARY(16) NULL COMMENT '服务端生成的稳定投影流 UUID；未建立投影的旧夹具允许为空',
    ADD COLUMN last_projection_seq BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '持 Session 锁与业务及批次同事务分配的最后已提交序号；0 为初始水位',
    ADD CONSTRAINT chk_session_projection_head CHECK (projection_stream_id IS NOT NULL OR last_projection_seq=0);

CREATE TABLE session_projection_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部主键，不作为投影游标',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 SessionId',
    stream_id BINARY(16) NOT NULL COMMENT '所属 Session 固定投影流 UUID',
    seq BIGINT UNSIGNED NOT NULL COMMENT '流内提交序号，从 1 开始；由 Session 锁分配',
    previous_seq BIGINT UNSIGNED NOT NULL COMMENT '直接前驱；首批为 0',
    protocol_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '固定 web-projection-v1',
    batch_payload MEDIUMBLOB NOT NULL COMMENT '完整批次精确 UTF-8 JSON 字节；不存凭据或原始 SDK 状态',
    byte_count INT UNSIGNED NOT NULL COMMENT '实际序列化 UTF-8 字节数，单批最多 1 MiB',
    batch_sha256 BINARY(32) NOT NULL COMMENT 'batch_payload 原始字节 SHA-256',
    change_count SMALLINT UNSIGNED NOT NULL COMMENT 'changes 数组项数，1～128',
    occurred_at DATETIME(6) NOT NULL COMMENT '业务事务发生时间，UTC；不用于排序',
    gmt_create DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '创建时固定，UTC；不授权修改已提交批次',
    PRIMARY KEY (id),
    UNIQUE KEY uk_projection_batch (session_id,stream_id,seq),
    CONSTRAINT chk_projection_event_seq CHECK (session_id>0 AND seq>0 AND previous_seq=seq-1),
    CONSTRAINT chk_projection_event_protocol CHECK (protocol_version='web-projection-v1'),
    CONSTRAINT chk_projection_event_bytes CHECK (byte_count>0 AND byte_count<=1048576 AND byte_count=OCTET_LENGTH(batch_payload)),
    CONSTRAINT chk_projection_event_count CHECK (change_count BETWEEN 1 AND 128),
    CONSTRAINT chk_projection_event_json CHECK (JSON_VALID(CONVERT(batch_payload USING utf8mb4)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='同业务短事务提交的不可变会话投影批次';

CREATE TABLE projection_content (
    content_id BINARY(16) NOT NULL COMMENT '服务端生成的稳定内容 UUID',
    owner_user_id BIGINT UNSIGNED NOT NULL COMMENT '所属稳定平台用户 ID',
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属业务 SessionId',
    run_id BIGINT UNSIGNED NOT NULL COMMENT '原业务 RunId；不将过程提升为正式历史',
    content_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'TEXT／IMAGE_REF／LIST／TABLE／RECEIPT／INTERACTION_REF',
    stream_id BINARY(16) NOT NULL COMMENT '所属投影流 UUID',
    first_seq BIGINT UNSIGNED NOT NULL COMMENT '内容首次出现的批次序号，永久固定',
    first_position SMALLINT UNSIGNED NOT NULL COMMENT '首次批内位置，从 1 开始，永久固定',
    current_version BIGINT UNSIGNED NOT NULL COMMENT '当前不可变展示版本，从 1 开始',
    status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'OPEN／CLOSED／PROMOTED',
    close_reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'COMPLETED／WAITING／FAILED／INTERRUPTED／PROMOTED',
    replacement_message_id BIGINT UNSIGNED NULL COMMENT '提升时替换预览的正式 ASSISTANT 消息，非工具或交互授权',
    row_version BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '内容头条件写入版本，从 1 开始',
    gmt_create DATETIME(6) NOT NULL COMMENT '创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '头指针修改时间，UTC',
    PRIMARY KEY (content_id),
    UNIQUE KEY uk_projection_anchor (session_id,stream_id,first_seq,first_position),
    KEY idx_projection_content_run (run_id),
    KEY idx_projection_content_owner (owner_user_id,session_id),
    CONSTRAINT chk_projection_content_positive CHECK (owner_user_id>0 AND session_id>0 AND run_id>0 AND first_seq>0
        AND first_position BETWEEN 1 AND 128 AND current_version>0 AND row_version>0),
    CONSTRAINT chk_projection_content_kind CHECK (content_kind IN ('TEXT','IMAGE_REF','LIST','TABLE','RECEIPT','INTERACTION_REF')),
    CONSTRAINT chk_projection_content_state CHECK (
        (status='OPEN' AND close_reason IS NULL AND replacement_message_id IS NULL)
        OR (status='CLOSED' AND close_reason IS NOT NULL AND close_reason IN ('COMPLETED','WAITING','FAILED','INTERRUPTED') AND replacement_message_id IS NULL)
        OR (status='PROMOTED' AND close_reason IS NOT NULL AND close_reason='PROMOTED' AND replacement_message_id IS NOT NULL AND replacement_message_id>0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='稳定展示身份、不可变首次锚点及当前版本头';

CREATE TABLE projection_content_version (
    content_id BINARY(16) NOT NULL COMMENT '稳定内容 UUID，逻辑引用内容头',
    content_version BIGINT UNSIGNED NOT NULL COMMENT '不可变版本号，从 1 开始',
    base_version BIGINT UNSIGNED NOT NULL COMMENT '直接前驱；首版为 0，其余为新版本减 1',
    payload_version INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '受控展示载荷格式，从 1 开始',
    change_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'CONTENT_SET／TEXT_APPEND／CONTENT_CLOSE',
    content_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '该版本的 OPEN／CLOSED／PROMOTED',
    close_reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '该版本封闭原因',
    replacement_message_id BIGINT UNSIGNED NULL COMMENT '该版本正式替换消息；只在 PROMOTED 时有值',
    body_format VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'plain-text／markdown-v1／json-v1',
    payload_storage VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'INLINE 精确正文或 FIXED_REF 固定不可变源',
    body MEDIUMBLOB NULL COMMENT '不可变完整 UTF-8 正文／结构快照；FIXED_REF 为空，绝不取最新代替',
    byte_count INT UNSIGNED NOT NULL COMMENT '完整正文实际 UTF-8 字节数；TEXT 最多 256 KiB，结构最多 64 KiB',
    body_sha256 BINARY(32) NOT NULL COMMENT '完整正文精确 UTF-8 字节 SHA-256',
    source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'RUN_PROGRESS／MESSAGE_BLOCK／MESSAGE_MEDIA／TOOL_OPERATION／INTERACTION_REVISION',
    source_id BIGINT UNSIGNED NOT NULL COMMENT '受控平台源主键；交互时为 requestId，不接受 URL',
    source_revision_id BIGINT UNSIGNED NULL COMMENT 'INTERACTION_REVISION 固定修订主键；其他源为空',
    interaction_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '交互引用固定类型；不是第二授权账本',
    projection_seq BIGINT UNSIGNED NOT NULL COMMENT '产生该版本的批次序号',
    projection_position SMALLINT UNSIGNED NOT NULL COMMENT '产生该版本的批内位置',
    summary_byte_count INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '实际结果摘要 UTF-8 字节数，上限 8 KiB；不代替完整正文长度',
    table_rows SMALLINT UNSIGNED NULL COMMENT 'TABLE 实际显示行数，最多 50',
    table_columns SMALLINT UNSIGNED NULL COMMENT 'TABLE 固定列数，最多 20',
    gmt_create DATETIME(6) NOT NULL COMMENT '不可变版本创建时间，UTC',
    gmt_modified DATETIME(6) NOT NULL COMMENT '创建时固定，UTC；不授权原地更新',
    PRIMARY KEY (content_id,content_version),
    CONSTRAINT chk_projection_version_positive CHECK (content_version>0 AND base_version=content_version-1 AND payload_version=1
        AND source_id>0 AND projection_seq>0 AND projection_position BETWEEN 1 AND 128),
    CONSTRAINT chk_projection_version_change CHECK (change_kind IN ('CONTENT_SET','TEXT_APPEND','CONTENT_CLOSE')
        AND (content_version<>1 OR change_kind='CONTENT_SET')),
    CONSTRAINT chk_projection_version_state CHECK (
        (content_status='OPEN' AND close_reason IS NULL AND replacement_message_id IS NULL)
        OR (content_status='CLOSED' AND close_reason IS NOT NULL AND close_reason IN ('COMPLETED','WAITING','FAILED','INTERRUPTED') AND replacement_message_id IS NULL)
        OR (content_status='PROMOTED' AND close_reason IS NOT NULL AND close_reason='PROMOTED' AND replacement_message_id IS NOT NULL AND replacement_message_id>0)),
    CONSTRAINT chk_projection_version_transition CHECK (
        (change_kind='CONTENT_SET') OR (change_kind='TEXT_APPEND' AND content_status='OPEN')
        OR (change_kind='CONTENT_CLOSE' AND content_status IN ('CLOSED','PROMOTED'))),
    CONSTRAINT chk_projection_version_format CHECK (body_format IN ('plain-text','markdown-v1','json-v1')),
    CONSTRAINT chk_projection_version_bytes CHECK (byte_count<=262144 AND
        ((payload_storage='INLINE' AND body IS NOT NULL AND byte_count=OCTET_LENGTH(body))
        OR (payload_storage='FIXED_REF' AND body IS NULL AND source_kind IN ('MESSAGE_BLOCK','INTERACTION_REVISION')))),
    CONSTRAINT chk_projection_version_source CHECK (
        (source_kind='INTERACTION_REVISION' AND source_revision_id IS NOT NULL AND source_revision_id>0
            AND interaction_type IS NOT NULL AND interaction_type IN ('FORM','OPERATION_CONFIRMATION'))
        OR (source_kind IN ('RUN_PROGRESS','MESSAGE_BLOCK','MESSAGE_MEDIA','TOOL_OPERATION') AND source_revision_id IS NULL AND interaction_type IS NULL)),
    CONSTRAINT chk_projection_version_budget CHECK (summary_byte_count<=8192 AND
        ((table_rows IS NULL AND table_columns IS NULL) OR (table_rows IS NOT NULL AND table_rows<=50 AND table_columns IS NOT NULL AND table_columns BETWEEN 1 AND 20)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='精确正文或固定源定位的不可变展示版本链';

ALTER TABLE run_progress
    ADD COLUMN payload_version INT UNSIGNED NULL COMMENT '新投影过程格式为 1；原探针未投影行为空',
    ADD COLUMN structured_payload JSON NULL COMMENT '受控阶段、工具或交互的展示元数据，不复制秘密及原始思考',
    ADD COLUMN content_id BINARY(16) NULL COMMENT '稳定展示内容 UUID',
    ADD COLUMN content_version BIGINT UNSIGNED NULL COMMENT '该过程固定展示版本',
    ADD COLUMN sdk_call_key BINARY(16) NULL COMMENT '可信 SDK 调用键，模型片段必需；阶段说明可空',
    ADD COLUMN source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT 'PLATFORM_STAGE／MODEL_TEXT／TOOL_FACT／INTERACTION_FACT',
    ADD COLUMN source_id BIGINT UNSIGNED NULL COMMENT '受控来源主键：Run／调用／工具／交互请求',
    ADD COLUMN first_projection_seq BIGINT UNSIGNED NULL COMMENT '内容首次锚点批次，不随版本更新',
    ADD COLUMN first_projection_position SMALLINT UNSIGNED NULL COMMENT '内容首次锚点位置',
    ADD COLUMN content_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本条对应版本的内容状态',
    ADD COLUMN close_reason VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '本条对应版本的关闭原因',
    ADD COLUMN replacement_message_id BIGINT UNSIGNED NULL COMMENT '本条提升时的正式消息关联',
    ADD COLUMN projection_stream_id BINARY(16) NULL COMMENT '所属投影流 UUID',
    ADD COLUMN projection_seq BIGINT UNSIGNED NULL COMMENT '本条过程与展示版本同事务产生的投影批次',
    ADD COLUMN projection_position SMALLINT UNSIGNED NULL COMMENT '批内固定位置',
    ADD CONSTRAINT chk_run_progress_projection CHECK (
        (payload_version IS NULL AND structured_payload IS NULL AND content_id IS NULL AND content_version IS NULL
            AND sdk_call_key IS NULL AND source_kind IS NULL AND source_id IS NULL AND first_projection_seq IS NULL
            AND first_projection_position IS NULL AND content_status IS NULL AND close_reason IS NULL AND replacement_message_id IS NULL
            AND projection_stream_id IS NULL AND projection_seq IS NULL AND projection_position IS NULL)
        OR (payload_version IS NOT NULL AND payload_version=1 AND content_id IS NOT NULL AND content_version IS NOT NULL AND content_version>0
            AND source_kind IS NOT NULL AND source_kind IN ('PLATFORM_STAGE','MODEL_TEXT','TOOL_FACT','INTERACTION_FACT')
            AND source_id IS NOT NULL AND source_id>0 AND first_projection_seq IS NOT NULL AND first_projection_seq>0
            AND first_projection_position IS NOT NULL AND first_projection_position BETWEEN 1 AND 128
            AND content_status IS NOT NULL AND content_status IN ('OPEN','CLOSED','PROMOTED')
            AND projection_stream_id IS NOT NULL AND projection_seq IS NOT NULL AND projection_seq>0
            AND projection_position IS NOT NULL AND projection_position BETWEEN 1 AND 128
            AND (source_kind<>'MODEL_TEXT' OR sdk_call_key IS NOT NULL))),
    ADD KEY idx_progress_projection (projection_stream_id,projection_seq,projection_position),
    ADD KEY idx_progress_content (content_id,content_version);
