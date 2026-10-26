## 隔离验证结果（2026-10-26）

阶段 0 / 03 隔离探针已完成。使用临时 MySQL 8.0.45（`--initialize-insecure`，不连业务库），锁定 SDK 2.0.4 + JDBC Store，受控 `request_user_input`（方法体内校验后挂起，非 `externalTool=true`）与无网络 ScriptedModel。

### 命令

```powershell
$env:JAVA_HOME = 'D:\code environment\jdk\jdk-17.0.9'
.\mvnw.cmd "-Dtest=PendingInteractionProbeTest" test
```

Tests run: 6, Failures: 0, Errors: 0, Skipped: 0。BUILD SUCCESS。

### 验收对照

| 验收要点 | 结论 |
| --- | --- |
| 受控输入校验并产生唯一受支持 pending；保存后独立读回与冻结 ToolUse／工具 ID／名称一致 | 通过 |
| 就绪须含调用结束／保存证据与一致持久 pending；仅事件不可 READY | 通过 |
| 合成回答重建匹配工具结果并续接；结合完整结果、保存与独立读回证明 CONSUMED | 通过 |
| 保存失败等窗口保持 RECONCILING，不开放可提交卡、不标已消费 | 通过 |
| 已领取未消费时租约／重启不自动重送，保留原回答 | 通过 |
| 分别记录就绪与消费结论；不冒充真实 Web 交互已通过 | 通过 |

### 证据

- `backend/evidence/issue26/验收记录.md`
- `backend/evidence/issue26/probe-summary.json`

### 未纳入

无执行 pending 终结（#27）、正式 FORM HTTP／前端／真实模型／会议、阶段 0 整体通过。本条仅隔离验证，未执行正式 DDL／业务库接入／提交部署。
