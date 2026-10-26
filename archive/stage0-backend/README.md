# 后端固定依赖准入（#24）

本目录当前只交付 [#24](https://github.com/BeyondzychenProton/agentscope-brain/issues/24) 的单 Maven 模块、封闭 draft-07 校验入口与隔离探针。业务启动入口、身份／会话／Run、正式库和前端留在各自工单。本模块尚不是可部署应用。

## 复现

在 PowerShell 7 中，从 `backend/` 执行，显式提供本机 Java 17 JDK；证据目录必须不存在且位于 `target/` 外：

```powershell
.\scripts\验证依赖基线.ps1 -JdkHome 'D:\code environment\jdk\jdk-17.0.9' -EvidenceDirectory "$env:TEMP\agentscope-issue24-new-run"
```

脚本使用 Maven Wrapper 3.3.4／Maven 3.9.16，顺序保存 effective POM、完整 verbose tree、实际 classpath、全套测试／自动配置、两次 clean 构建的 JAR 摘要、字节码和依赖／源码指纹。Java 17／Maven 精确版本由 Enforcer 检查；每次运行应同时记录实际 JDK 的发行商、补丁与制品校验，不要求使用特定发行商。

可单独执行 `./mvnw.cmd -Dtest=SdkToolValidatorTest test`、`./mvnw.cmd -Dtest=ClosedDraft7ValidatorTest test` 或 `./mvnw.cmd -Dtest=IsolatedAssemblyTest test`。先设置 `JAVA_HOME`，不要让系统 Java 8 shim 代替目标编译器。Windows Wrapper 从官方 Central URL 下载 ZIP；SHA-256 来自已与 Apache 官方 SHA-512 核对的真实分发包。已解压 Wrapper 缓存需由本机管理，缓存命中不会重新核验 ZIP；Unix 执行另要求 `unzip`，本工单未验收 Unix。

## 探针边界

- SDK 测试直接调用已发布 `ToolValidator.validateInput`，保留原生 draft-2020-12 与 String／InputFormat 接缝。
- 应用测试使用 `ClosedDraft7Validator`，固定 draft-07、开启 format／全部错误、禁止宽松类型；拒绝引用、标识符及方言替换，只允许内建 meta-schema。required／additionalProperties 补齐转义后的字段路径。
- `ClosedDraft7Validator` 是本条依赖准入入口。后续 form-v1 仍须先完成协议／结构／资源白名单、严格 `+08:00` 秒表示、候选归属及业务校验；不得仅凭此入口开放模型任意 Schema。
- Boot 探针实际启动 127.0.0.1 临时 servlet 端口，使用仅 test scope 的内存 H2 和 `SELECT 1`；SQL 初始化及 Session 建表／清理均关闭。没有业务表或账号。MinIO 只构造客户端，未调用网络方法；模型／MCP 均不调用。H2 不构成 MySQL 运行验收。

## 当前结论

[验收记录](evidence/issue24/验收记录.md)：用户确认本次使用现有 Oracle JDK 17。官方 Windows 17.0.9 ZIP 的 SHA-256 已通过校验，本机全部 416 个文件与该制品逐项一致；结合已通过的构建／隔离探针，#24 的范围内验收已完成。Temurin 是部署候选，缺少其 Windows 资产不阻塞本条；其他阶段 0 工单和实际上线目标验收仍分别进行。
