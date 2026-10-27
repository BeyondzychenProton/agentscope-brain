# AgentScope Brain 正式后端

单 Spring Boot 应用、单 Maven 模块、一个生产 Java 进程。按业务能力组织代码，完整规则见 [后端开发规范](开发规范.md)；领域术语沿用 [GLOSSARY.md](../GLOSSARY.md)。

当前已实现 #34～#36 的登录、首次改密与固定认证期限，以及 #37 的真实身份／能力读取和受控 TEXT 历史。唯一启动类和九模块布局保持不变；真实发送、完整投影恢复、会议和在线存储仍按各自工单实施。缺必要结构／预置／配置时返回受控不可用，不会生成默认用户或修复数据。

```text
backend/
├── pom.xml / mvnw / mvnw.cmd / .mvn/  # 唯一正式 Maven 构建入口
├── src/main/java/com/agentscope/brain/
│   ├── AgentScopeBrainApplication.java
│   └── identity|release|conversation|media|interaction|operation|runtime|projection|memory/
├── src/main/resources/                # 无秘密的 dev、prod HTTP/Cookie 差异
├── src/test/java/                     # 正式测试，按生产包对应组织
├── src/test/resources/                # 自动化测试专用配置／夹具
├── config/application.yaml           # 共用资源的实际值、运行环境与中文说明
├── scripts/                          # 工程检查脚本
├── resource.txt                      # 私有资源交接，Git 忽略，不打包
└── 开发规范.md
```

业务模块当前用 `package-info.java` 落实职责；`api/` 与 `internal/` 随真实用例建立，不批量生成空 Controller／Service／Mapper。尚未接入业务的 draft-07 兼容性校验辅助放在 `src/test/java/` 的对应包中，不随应用发布。

## 构建与检查

在 PowerShell 中，从 `backend/` 执行，显式选择已核验 Java 17：

先按 [正式前端说明](../frontend/README.md) 完成精确运行时下的安装、类型检查与生产构建；Maven 把 `frontend/dist` 打入同源 JAR，缺少前端产物时拒绝正式打包。

```powershell
$env:JAVA_HOME = 'D:\code environment\jdk\jdk-17.0.9'
.\scripts\检查工程规范.ps1
.\mvnw.cmd -B -ntp verify
```

生成 `target/agentscope-brain-backend-0.1.0-SNAPSHOT.jar`，使用 Maven 3.9.16 和锁定依赖。构建不会执行共用库 DDL、装载 `resource.txt` 或连接模型／MCP／MinIO。自动化测试使用进程内 H2 与回环 HTTP；这不代替 MySQL 或真实登录验收。

已有进程占用默认 JAR 时，可用 `-Dbrain.build.directory=target/issue37-build` 独立构建，避免替换运行中的制品；`verify` 的正式产物检查随目录一起切换。

## 运行配置

直接编辑 [config/application.yaml](config/application.yaml)：已有连接、账号、密码已填写，运行环境由 `spring.profiles.active` 的 `dev`／`prod` 切换，不需要设置资源环境变量。从本目录运行：

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar .\target\agentscope-brain-backend-0.1.0-SNAPSHOT.jar
```

Spring Boot 自动读取本目录下的 `config/application.yaml`；不再使用模板或额外 import。实际文件已被 Git 忽略且不打入 JAR；从其他工作目录启动须用 `--spring.config.additional-location=file:/绝对路径/application.yaml` 明确位置。更多说明见 [配置说明](config/README.md)。

真实 `agentscope_brain` 已完成 #29 预置，#34 按用户补充授权新增 SDK／Spring Session 原生表，并完成真实账号 Web 受限登录与后端重启恢复；证据见 [#34 验收记录](../verification/issue-34/验收记录.md)。初始化、自动建表和 Session 清理仍关闭；JDBC 会话使用 IMMEDIATE 保存，固定绝对期限另由可信认证时间检查。

启动把平台 01～06 登录结构基线、SDK 和 Session 三类实际元数据分别与 `readiness/structure-v1.tsv` 核对，再核验普通用户、来源／凭据／号码、授权／当前发布、发布／绑定／Skill／资源摘要以及本机受控引用。文件 `config/release-catalog.private.json` 保存 #29 冻结引用清单，已被 Git 忽略、不打包；也可用私有 YAML 的 `brain.bootstrap.reference-catalog` 指向受控本地文件。合法停用／撤权是策略状态，不是数据缺失。检查不连接模型、MCP 或 MinIO。

默认 `verify` 使用 H2／合成数据。MySQL 与生产 JAR 的受限登录验收显式运行仓库根目录 `python db/tests/执行Web受限登录隔离验收.py`，它只创建并清理自己核验过的回环 MySQL；不读取共用 YAML。真实数据集不用该故障执行器。

## 生产与测试隔离

#37 的 `/api/me` 与 `/api/employees/{employeeId}/capabilities` 只读当前授权及固定发布配置；`EmployeeAccess.requireExecution` 重新检查当前账号／安全上下文与授权，不接收浏览器能力作为凭据。尚未实现的写端点仍由服务端拒绝。

登录后通过 `/workbench?sessionId=<本人业务会话ID>` 打开指定历史。TEXT 读取沿用 `projection/snapshot`、`projection/messages`、`projection/content/{contentId}`；快照及分页使用 REPEATABLE READ，只读正式消息和固定不可变展示版本，校验 UTF-8 长度／SHA-256，按固定消息上界翻页。页面对用户与员工文字均作字面展示，扩展 Markdown、SSE、完整运行／交互恢复由阶段 2 实施；超出当前 TEXT 切片的事实返回 `PROJECTION_SCOPE_UNAVAILABLE`，不返回伪完整恢复结果。

启动支持两个精确结构修订：原 `structure-v1.tsv`（01～06＋原生表）及 `structure-workbench-v1.tsv`（01～10＋原生表）。任一历史投影表存在即要求完整后者；部分安装拒绝就绪。旧登录基线可继续读取身份与能力，缺历史修订时返回受控不可用，不自动 DDL。`brain.projection.cursor-key` 可配置至少 32 字节的 Base64 私有 HMAC key，使原分页定位在重启后继续有效；未配置时使用进程随机 key，旧定位需重新取快照，历史事实和 streamId 不变。

显式运行 `python db/tests/执行工作台撤权只读隔离验收.py` 验证 #37；它使用独占、可销毁的回环 MySQL，不读共享 YAML、不改共享账号。`--browser` 在撤权夹具上等待真实浏览器观察，证据放 [#37 验收记录](../verification/issue-37/验收记录.md)。

首次改密（#35）使用短平台事务锁定用户后更新凭据／安全版本／用户修改来源并追加身份审计，随后轮换 Session ID／CSRF，保存沿用原认证时间与绝对期限的新上下文。提交异常只独立核对本请求的编码哈希、版本及审计，不重跑改密；无法证明时安全退出并返回 `AUTH_OUTCOME_UNCERTAIN`。SQL 已提交但 Session 保存失败为 `PASSWORD_CHANGED_RELOGIN_REQUIRED`；响应等待请求末尾 Session JDBC 保存完成，避免提前返回成功。默认测试覆盖提交回执丢失、原子回滚、并发与最终保存失败；真实 MySQL 隔离验证显式运行 `python db/tests/执行首次改密隔离验收.py`，不读取共用 YAML 或改真实账号。

固定 24 小时绝对期限、活动不续期、多浏览器并行与仅退出当前会话（#36）沿用登录时写入的 `authenticatedAt`／`absoluteExpiresAt`；服务端用 `Clock` 判定，隔离验收可通过 `brain.auth.clock-instant`／`brain.auth.clock-offset` 夹具覆盖恰到期边界，不缩短 `Duration.ofHours(24)`。显式运行 `python db/tests/执行认证期限与并行退出隔离验收.py`；受控时钟只用于自建回环实例，不与 #37 撤权／停用在同一真实数据集并发。

- `src/main/` 仅保留已装配的运行逻辑及必要资源。`StartupReadiness`、发布完整性校验和结构基线是实际启动准入，不是测试探针，必须随应用保留。
- 单元测试、依赖兼容性测试及其辅助类统一放 `src/test/java/`，合成配置／夹具放 `src/test/resources/`。历史探针仍在仓库 `archive/`，数据库隔离执行器仍在 `db/tests/`，均不进入生产 JAR。
- 工程检查拒绝生产源码中的测试类命名、测试目录、测试库导入和测试资源。`mvnw verify` 在打包后另执行 1 项产物隔离检查，核对所有编译后的测试类、测试配置及测试依赖未进入正式 JAR；`target/` 中的测试编译输出和报告属于可再生构建结果。

## 历史验证

#24—#28 的探针与验收材料已移至 [阶段 0 归档](../archive/stage0-backend/ARCHIVE.md)。它们不再进入正式构建。后续新证据放到仓库 `verification/issue-<编号>/`，临时构建报告放 `target/`；原始历史证据保持不变。
