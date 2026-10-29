-- 已安装 schema 14 的数据库显式执行本迁移；应用不会自动改表。
-- Copilot 文本链路的 Run 标识是 UUID，复用不了 business_run 的 bigint 主键，
-- 因此写操作提案与一次性确认补 copilot_run_id，并与 run_id 二选一。

ALTER TABLE tool_operation
    MODIFY COLUMN run_id BIGINT UNSIGNED NULL COMMENT '普通链路 business_run.id；Copilot 链路为空',
    ADD COLUMN copilot_run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Copilot 文本线程 Run UUID；普通链路为空' AFTER run_id,
    ADD CONSTRAINT chk_tool_operation_run_binding
        CHECK ((run_id IS NULL) <> (copilot_run_id IS NULL)),
    ADD KEY idx_tool_operation_copilot_run (copilot_run_id, status);

ALTER TABLE operation_confirmation
    MODIFY COLUMN run_id BIGINT UNSIGNED NULL COMMENT '普通链路 business_run.id；Copilot 链路为空',
    ADD COLUMN copilot_run_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'Copilot 文本线程 Run UUID；普通链路为空' AFTER run_id,
    ADD CONSTRAINT chk_confirmation_run_binding
        CHECK ((run_id IS NULL) <> (copilot_run_id IS NULL)),
    ADD KEY idx_confirmation_copilot_run (copilot_run_id, status);
