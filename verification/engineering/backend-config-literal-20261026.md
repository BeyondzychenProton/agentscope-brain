# 直接 YAML 配置调整

日期：2026-10-26，Asia/Shanghai。用户要求配置信息直接写明在 YAML 中，不使用资源环境变量占位符。

- 实际配置统一为本机 `backend/config/application.yaml`，已按 `resource.txt` 填入 MySQL、MinIO、Redis 的已知连接与账号密码，并加中文说明；此文件已被 Git 忽略、不打入 JAR。本记录不复制实际值。
- 删除 example 模板和 classpath 中的额外 import 文件；从 `backend/` 启动时使用 Spring Boot 标准 `config/application.yaml` 发现方式。运行环境直接在 YAML 中填写 `dev` 或 `prod`，当前为 `dev`。
- MySQL 数据库名、MinIO bucket／对象前缀原文件未提供，明确标注待确认，不猜测共享服务中的实际目标。MinIO API 未提供协议，当前按同机控制台的 HTTP 填写并在 YAML 中注明待接入核实。
- Redis、MinIO 仅登记配置，未启用在线客户端；自动 SQL 初始化、Session 建表与清理保持关闭。
- README、配置说明及开发规范已同步更新。

验证：

1. 工程目录／模块检查通过，相关三份文档相对链接无缺失。
2. 使用没有自动配置和资源 Bean 的最小 Spring 上下文，验证标准配置发现、YAML 字面凭据与原资源一致、dev profile／Cookie 差异生效，以及自动初始化关闭；未创建 DataSource 或连接在线资源。
3. `mvnw.cmd -B -ntp clean verify` 成功；14 项隔离测试通过，失败／错误／跳过均 0。
4. JAR 无本地实际配置、已移除的 classpath application.yaml、资源交接文件或测试配置；清除了旧构建残留。

以上只证明配置格式、读取方式与工程构建，不代表共享 MySQL／MinIO／Redis 已在线验收或阶段 1 已完成。没有初始化业务数据、部署、Git 提交或推送。
