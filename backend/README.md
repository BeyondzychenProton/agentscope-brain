# AgentScope Brain 正式后端

单 Spring Boot 应用、单 Maven 模块、一个生产 Java 进程。按业务能力组织代码，完整规则见 [后端开发规范](开发规范.md)；领域术语沿用 [GLOSSARY.md](../GLOSSARY.md)。

当前交付是正式开发骨架：唯一启动类、模块目录、共用资源 YAML、构建入口和最小隔离测试。登录、业务数据库初始化、对话、会议和在线存储仍按各自工单实施。登录链实现前，HTTP 全部拒绝且不会生成默认用户。

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

业务模块当前用 `package-info.java` 落实职责；`api/` 与 `internal/` 随真实用例建立，不批量生成空 Controller／Service／Mapper。已有 Schema 校验实现位于 `interaction/internal/validation/`。

## 构建与检查

在 PowerShell 中，从 `backend/` 执行，显式选择已核验 Java 17：

```powershell
$env:JAVA_HOME = 'D:\code environment\jdk\jdk-17.0.9'
.\scripts\检查工程规范.ps1
.\mvnw.cmd -B -ntp verify
```

生成 `target/agentscope-brain-backend-0.1.0-SNAPSHOT.jar`，使用 Maven 3.9.16 和锁定依赖。构建不会执行共用库 DDL、装载 `resource.txt` 或连接模型／MCP／MinIO。自动化测试使用进程内 H2 与回环 HTTP；这不代替 MySQL 或真实登录验收。

## 运行配置

直接编辑 [config/application.yaml](config/application.yaml)：已有连接、账号、密码已填写，运行环境由 `spring.profiles.active` 的 `dev`／`prod` 切换，不需要设置资源环境变量。确认其中标注的数据库名、bucket 等未提供项后，从本目录运行：

```powershell
& "$env:JAVA_HOME\bin\java.exe" -jar .\target\agentscope-brain-backend-0.1.0-SNAPSHOT.jar
```

Spring Boot 自动读取本目录下的 `config/application.yaml`；不再使用模板或额外 import。实际文件已被 Git 忽略且不打入 JAR；从其他工作目录启动须用 `--spring.config.additional-location=file:/绝对路径/application.yaml` 明确位置。更多说明见 [配置说明](config/README.md)。

该命令是后续运行入口说明，本次没有连接共用资源启动业务应用。当前启动仍需补齐真实数据库名；数据库结构／初始化和正式登录尚待阶段 1。自动 SQL 初始化、Session 建表和 Session 清理保持关闭。

## 历史验证

#24—#28 的探针与验收材料已移至 [阶段 0 归档](../archive/stage0-backend/ARCHIVE.md)。它们不再进入正式构建。后续新证据放到仓库 `verification/issue-<编号>/`，临时构建报告放 `target/`；原始历史证据保持不变。
