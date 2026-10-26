# 锁定依赖的原生结构

与 `db/schema/` 平台 DDL 分开管理，不套用平台表的无外键／注释约束。

- `agentscope-2.0.4-mysql.sql`：来自 `agentscope-extensions-jdbc:2.0.4` 的 `MysqlDialect`，保留默认 `agentscope_` 前缀及三类表结构。仅去掉 `IF NOT EXISTS`，由显式执行前检查拒绝既有同名表；不把存在当作结构正确。Java 契约测试用实际锁定方言输出逐项核对。
- `spring-session-4.1.1-mysql.sql`：逐字节取自实际锁定 `spring-session-jdbc:4.1.1` JAR 的 `org/springframework/session/jdbc/schema-mysql.sql`，包含原生外键／索引；Java 契约测试与 JAR 字节比对。

#34 已经用户明确批准在现有 `agentscope_brain` 执行一次，见 [执行范围](../../verification/issue-34/真实结构执行范围.md)和[独立执行收据](../../verification/issue-34/real-native-structure.json)。不要重跑这些 CREATE 或以默认 SDK 自动建表代替部署检查。应用启动只读；Spring Session 自动初始化与清理保持关闭。

登录期望元数据由 `python db/tests/生成登录结构期望.py` 在自建回环 MySQL 上，从平台 01～06 和这两份原生 DDL 生成；产物带源文件 SHA-256，检查列、类型／默认／排序规则／生成列、索引、CHECK 正文、引擎和原生外键。源结构修订应重新生成并同时验证实际目标，不能直接手改期望绕过缺表。
