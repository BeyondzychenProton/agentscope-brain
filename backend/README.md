# AgentScope Brain 正式后端

单 Spring Boot 应用、单 Maven 模块、一个生产 Java 进程。按业务能力组织代码，完整规则见 [后端开发规范](开发规范.md)；领域术语沿用 [GLOSSARY.md](../GLOSSARY.md)。

当前已实现 #34 的普通用户登录、CSRF、Session JDBC、当前受限身份、受限页面及启动只读检查；唯一启动类和九模块布局保持不变。首次改密提交由 #35 承担，对话、会议和在线存储仍按各自工单实施。缺必要结构／预置／配置时 `/readyz` 和认证 API 返回 503，不会生成默认用户或修复数据。

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

## 运行配置

直接编辑 [config/application.yaml](config/application.yaml)：已有连接、账号、密码已填写，运行环境由 `spring.profiles.active` 的 `dev`／`prod` 切换，不需要设置资源环境变量。从本目录运行：

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar .\target\agentscope-brain-backend-0.1.0-SNAPSHOT.jar
```

Spring Boot 自动读取本目录下的 `config/application.yaml`；不再使用模板或额外 import。实际文件已被 Git 忽略且不打入 JAR；从其他工作目录启动须用 `--spring.config.additional-location=file:/绝对路径/application.yaml` 明确位置。更多说明见 [配置说明](config/README.md)。

真实 `agentscope_brain` 已完成 #29 预置，#34 按用户补充授权新增 SDK／Spring Session 原生表，并完成真实账号 Web 受限登录与后端重启恢复；证据见 [#34 验收记录](../verification/issue-34/验收记录.md)。初始化、自动建表和 Session 清理仍关闭；JDBC 会话使用 IMMEDIATE 保存，固定绝对期限另由可信认证时间检查。

启动把平台 01～06 登录结构基线、SDK 和 Session 三类实际元数据分别与 `readiness/structure-v1.tsv` 核对，再核验普通用户、来源／凭据／号码、授权／当前发布、发布／绑定／Skill／资源摘要以及本机受控引用。文件 `config/release-catalog.private.json` 保存 #29 冻结引用清单，已被 Git 忽略、不打包；也可用私有 YAML 的 `brain.bootstrap.reference-catalog` 指向受控本地文件。合法停用／撤权是策略状态，不是数据缺失。检查不连接模型、MCP 或 MinIO。

默认 `verify` 的 23 项测试只用 H2／合成数据。MySQL 与生产 JAR 的受限登录验收显式运行仓库根目录 `python db/tests/执行Web受限登录隔离验收.py`，它只创建并清理自己核验过的回环 MySQL；不读取共用 YAML。真实数据集不用该故障执行器。

## 生产与测试隔离

- `src/main/` 仅保留已装配的运行逻辑及必要资源。`StartupReadiness`、发布完整性校验和结构基线是实际启动准入，不是测试探针，必须随应用保留。
- 单元测试、依赖兼容性测试及其辅助类统一放 `src/test/java/`，合成配置／夹具放 `src/test/resources/`。历史探针仍在仓库 `archive/`，数据库隔离执行器仍在 `db/tests/`，均不进入生产 JAR。
- 工程检查拒绝生产源码中的测试类命名、测试目录、测试库导入和测试资源。`mvnw verify` 在打包后另执行 1 项产物隔离检查，核对所有编译后的测试类、测试配置及测试依赖未进入正式 JAR；`target/` 中的测试编译输出和报告属于可再生构建结果。

## 历史验证

#24—#28 的探针与验收材料已移至 [阶段 0 归档](../archive/stage0-backend/ARCHIVE.md)。它们不再进入正式构建。后续新证据放到仓库 `verification/issue-<编号>/`，临时构建报告放 `target/`；原始历史证据保持不变。
