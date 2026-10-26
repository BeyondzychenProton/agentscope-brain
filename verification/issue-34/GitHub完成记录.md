## #34 完成记录（2026-10-26）

普通平台用户已通过正式 Vue Web → Spring Boot → 实际 MySQL 凭据完成登录，进入受限改密页。服务端和浏览器路由均拒绝提前访问历史、素材、订阅、能力或创建 Run；`me` 仅返回服务器确定的必要受限身份。首次改密提交仍由 #35 承担。

### 实现与结构

- 建立 `frontend/`，固定 Node 24.22.1／npm 11.19.1 及全部直接依赖，保存完整锁文件；独立类型检查、生产构建通过，由正式后端 JAR 同源提供页面。
- 实现 CSRF、手机号／限定 bcrypt 密码认证、Session ID／CSRF 轮换、JDBC 立即保存、受限 me、退出及受限路由。不存在的号和错误密码同为受控 401；认证数据／Session 基础设施不可用为 503。密码不进入会话或浏览器持久存储。
- 启动分别核验平台 01～06 登录结构、SDK 三表、Session 两表的真实元数据、必要预置引用及发布／绑定／Skill／资源摘要；只读装载本机受控引用和秘密配置。缺项同时拒绝 `/readyz`、认证入口与 Boot 内部可接流量状态；合法停用／撤权不当作结构损坏。模型／MCP／MinIO 网络成功不作为启动或登录前置。
- 用户本轮明确批准补充原生结构：现有 MySQL 8.4.11 `agentscope_brain` 从 30 → 35 表，新增 `agentscope_store`、`agentscope_sessions`、`agentscope_snapshots`、`SPRING_SESSION`、`SPRING_SESSION_ATTRIBUTES`。SDK DDL 与锁定 2.0.4 实际方言核对，Session DDL 与锁定 4.1.1 JAR 逐字节一致。8 条 DDL 分别记录，独立读回通过；原平台 30 表／22 行完全不变。

### 验证

| 层级 | 结果 |
| --- | --- |
| 正式构建／Java | 工程规范检查、23 项测试、正式 JAR 打包通过；JAR 没有私有配置、resource.txt 或测试／探针类 |
| 隔离 MySQL 8.0.45 + 正式 JAR | 最终 32 项 HTTP／JDBC／就绪验证通过，包括直接业务拒绝、错误分类、旧 token、安全版本失效、会话跨进程恢复、缺表／错结构／坏预置拒绝就绪、合法策略状态与 prod Cookie；外部计数服务请求为 0 |
| 真实开发 MySQL 8.4.11 | 最终 17 项检查通过；原账号认证、JDBC 安全上下文独立读回、后端进程重启后恢复原身份／原到期时间、受限业务直接请求拒绝、真实浏览器登录／刷新／路由拦回／退出均通过 |
| 真实数据保护 | 密码、首次改密标记、授权、发布及原平台 22 行均未改；没有 Run 或其他业务写。验证结束本轮登录 Session／认证上下文均为 0，本轮应用进程和临时 MySQL 已停止 |

最终 JAR SHA-256：`4a82400232795c2641dc151d9b9b8eb7717fdad19a819e724deb6cc1da9dd775`。构建、最终隔离验证和最终真实验证收据使用同一指纹。原预置批次 1 的 manifest 仍为 `7c3b58cb4b61a57905bec78e17a3821825c400785f0dfb0fd7147a008e70764f`。

本地证据集中在 `verification/issue-34/`：`验收记录.md`、`build-validation.json`、`backend-dependencies.txt`、`isolated-login.json`、`real-readonly-preflight.json`、`real-native-structure.json`、`real-login.json`、`真实结构执行范围.md` 及必要掩码截图。代码与证据目前仍在工作区，未提交／推送，不能把路径当作已经发布的仓库制品链接。秘密及完整号码未进入公开材料。

本结论覆盖 #34 的开发、隔离验证和真实开发数据集验收。真实浏览器为 Codex 内置 Chromium；生产 HTTPS／可信代理、完整 Chrome／Edge 矩阵及认证期限／退出完整验收仍由相应后续范围承担。#35 继续首次改密，#36／#37 按各自前置继续；没有交付对话／SSE／模型或工具执行，没有部署，不宣称阶段 1 整体通过。来源 #14／#1／#23 与原生关系保持原样。
