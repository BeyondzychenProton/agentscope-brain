## 隔离验证结果（2026-10-26）

阶段 0 / 05 固定版本接缝核验已完成。使用锁定 SDK 2.0.4 + H2 上真实 `JdbcStore`（`H2Dialect`，`initializeSchema=true`）。这不是 MySQL 真实验收；不以 KV 中有一行或本工单关闭宣称完整隔离协议通过。

### 命令

```powershell
$env:JAVA_HOME = 'D:\code environment\jdk\jdk-17.0.9'
.\mvnw.cmd "-Dtest=MemorySeamTest" test
```

Tests run: 9, Failures: 0, Errors: 0, Skipped: 0。BUILD SUCCESS。2026-10-26T10:09:54+08:00。

### 验收对照

| 验收要点 | 结论 |
| --- | --- |
| 固定 v2.0.4 重新核验 namespace／路径前缀与五条原生写入口类；记录默认日记键留下前导斜杠、默认 namespace 无代次／员工 | 通过 |
| `putIfVersion` 的 `false` 歧义；独立读回区分 EMPTY／MISSING／PRESENT | 通过 |
| 可信用户×数字员工×代次记忆、用户×数字员工×业务会话运行、只读发布资产；路径／RuntimeContext 不充当授权 | 通过 |
| 全局水位与 `memory/.consolidation_state` 重定向当前代次；不读旧代次／其他用户；候选键不是日记输入 | 通过 |
| 无准入原生写静默拒绝且不破坏后续 MANUAL_SAVE；FLUSH／CONSOLIDATE／MANUAL_SAVE 分路径准入；发布只读 | 通过 |
| 逐项列出源码核验、最小接口结果、阶段 2 待验与阻塞能力 | 见证据 |

### 证据

- `backend/evidence/issue28/验收记录.md`
- `backend/evidence/issue28/probe-summary.json`
- `backend/evidence/issue28/接缝核验.md`

### 阶段 2 待验（本条缺口只阻断对应记忆／Workspace 能力）

真实 MySQL 往返与排序规则、两阶段激活、并发／旧代次隔离、压缩拒绝端到端、成功 Run 学习。

### 未纳入

记忆管理 UI／API、真实学习任务、留存清理、正式 DDL／业务库接入、提交部署、阶段 0 整体通过。
