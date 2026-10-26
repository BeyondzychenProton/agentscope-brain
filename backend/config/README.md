# 共用资源配置

实际配置集中在 [application.yaml](application.yaml)，已按 `../resource.txt` 直接填入 MySQL、MinIO、Redis 的已知地址和账号密码，并给每个配置项写明中文说明。没有资源环境变量占位符，也不需要复制 example 文件。

直接修改 YAML：

| 位置 | 内容 |
| --- | --- |
| `spring.profiles.active` | 开发写 `dev`，生产改为 `prod`；两者共用同一套服务连接 |
| `spring.datasource` | MySQL JDBC URL、驱动、账号与密码；目标 `agentscope_brain` 已按 #29 授权初建 30 表并完成单普通用户／完整发布预置 |
| `brain.resources.minio` | 对象 API、管理控制台、访问账号密码；私有桶 `agentscope-brain` 已创建，使用 `originals/`，匿名访问拒绝且没有上传对象 |
| `brain.resources.redis` | Redis 地址、端口、密码；当前 `enabled: false`，既定认证仍使用 Session JDBC |
| `brain.resources.model` | 用户提供的 DashScope 兼容接口、API Key 与规范模型 ID `qwen3.8-max`；本轮尚无实际模型调用证据 |
| `brain.resources.meeting-mcp` | 更新对接文档中的测试环境地址、协议及鉴权凭据；已完成独立 HTTP 只读联通测试，尚非正式 Adapter 装配 |
| `spring.sql.init` / `spring.session.jdbc` | 禁止自动初始化、建表和当前阶段的 Session 清理；会话使用 IMMEDIATE 保存 |
| `server` / `mybatis-plus` | HTTP 端口、基础 Cookie、错误输出与 Mapper 位置 |

从 `backend/` 运行，Spring Boot 自动加载 `config/application.yaml`。开发／生产 HTTP 与 Cookie 差异分别来自 `src/main/resources/application-dev.yaml` 和 `application-prod.yaml`，不需要额外 import。

MySQL 库名、MinIO bucket 和对象前缀已由用户逐项确认并明确授权执行，见仓库 `verification/issue-29/真实初始化验收记录.md`。真实目标已完成初始化、独立读回和同源只读重复；默认私有桶无公共策略，匿名列举为 403。尚无图片原件上传／恢复证据；控制台地址不能用来替代对象 API。#34 经补充授权新增五张 SDK／Session 原生表，正式应用启动、真实普通用户受限登录和重启恢复已验收；不能重跑初建 DDL。

`release-catalog.private.json` 保留 #29 已冻结的受控引用正文与摘要，默认相对 `backend/` 装载，Git 忽略且不打包。需要其他本机位置时在同一私有 YAML 写 `brain.bootstrap.reference-catalog`。启动只读核验引用和必要秘密配置，不探测模型／MCP／MinIO。原件与口令留在私有配置，不复制到公开证据。

该文件含实际凭据，已被 Git 忽略且位于 Maven resources 之外，不打入 JAR。`resource.txt` 保留为本地交接原件。自动化测试显式只装载 `application-test.yaml`，不读取这里的实际配置。

共用同一数据区时按生产数据处理；本次仅在明确批准的库和桶内执行初始化，没有清理其他数据。实际值只保留在本机私有 YAML，不复制到提交材料。MCP 当前为测试环境，线上凭据按提供方说明另行准备；确认加密密钥仍在所属阶段准备，不补造尚未提供的值。初始化完成不代表正式 Java 用户链已经装配。
