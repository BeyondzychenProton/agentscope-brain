# 后端生产／测试隔离整理

日期：2026-10-27。对应 [GitHub #39](https://github.com/BeyondzychenProton/agentscope-brain/issues/39)。

检查确认，原有 JUnit 测试都已放在 `backend/src/test/`；生产目录中 `ClosedDraft7Validator` 仅被测试调用，未注册为 Spring Bean，也没有真实业务调用者。本轮把这一兼容性辅助迁到测试包，并采用包内可见性。有效测试保留，没有删除测试覆盖。

`StartupReadiness`、`PresetReleaseReadiness`、认证里的密码时间差防护及结构基线属于实际运行边界，继续留在生产目录。没有因其包含检查或校验而关闭它们，也没有改变登录、数据库、固定发布或资源访问行为。

## 本次变更

- `ClosedDraft7Validator.java`：从 `src/main/java` 迁到 `src/test/java` 的同一包。仅调整说明和类可见性，校验行为未变。
- `scripts/检查工程规范.ps1`：阻止测试类／测试目录、测试库导入及测试／私有配置进入 main。
- `ProductionArtifactIT`：放在 test，由 Maven Failsafe 在打包后执行。比较生产 JAR 与全部测试编译类，检查测试资源和测试依赖不随应用发布。
- README／开发规范：说明运行校验、单元测试、辅助类、SQL 探针、历史归档和构建输出的职责。

## 验证

为排除工作区同时进行的其他功能写入，用基线提交 `efa4dc6c16fa581acc13359dcc7c7ba876f01643` 导出独立副本，仅叠加本次变更与现有前端产物。校验变更文件字节与工作区一致后，执行工程检查和 `mvnw.cmd -B -ntp clean verify`。

| 检查 | 实测 |
| --- | --- |
| 工程目录、包名、模块依赖与测试隔离 | 通过 |
| 原有单元／兼容性／装配测试 | 23 项通过，0 失败／错误 |
| 打包后生产 JAR 隔离 | 1 项通过，测试辅助不在生产 JAR |
| 故意向副本 JAR 注入测试辅助类 | 检查明确失败，未误报成功 |
| 恢复原 JAR 后重新执行产物检查 | 通过 |
| 共享资源操作 | 无；未读取真实配置、未连接共享数据库或外部服务 |

构建副本的 JAR SHA-256 为 `fd8b9ce4548511260465b120f45b1ea6bba1eddd78902a1dcb3d9b2d246918cd`。源码、结果和副本范围见同名 JSON；详细临时日志在 `.codex-local/test-isolation-20261027/`，不进入提交和生产包。

原 #34 验收材料记录当时的代码和 JAR，保留原样。本轮是后续目录隔离修正，不复用旧 JAR 指纹冒充新构建，也没有重跑真实库 DDL／登录探针。工作区其他会话／素材／运行功能及原暂存文档不纳入本次提交；本轮不推送、不部署。
