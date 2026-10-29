-- 已安装 schema 13 的数据库显式执行本迁移；应用不会自动改表。
-- 增量扩展保留原有正文与用户历史，旧消息 display_json 为 NULL。
ALTER TABLE copilot_thread_message
    ADD COLUMN display_json LONGTEXT NULL COMMENT 'AG-UI 类型化展示消息，保留稳定消息与工具调用标识' AFTER content;
