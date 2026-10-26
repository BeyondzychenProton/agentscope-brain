## 隔离验证结果（2026-10-26）

阶段 0 / 04 隔离验证已完成。使用临时 MySQL 8.0.45（`--initialize-insecure`，不连业务库），锁定 SDK 2.0.4 + JDBC Store。终结路径仅 `getVersioned` → 状态变换 → `saveIfVersion`，不经 `call`／`stream`／空输入／`clearContext`。

### 命令

```powershell
$env:JAVA_HOME = 'D:\code environment\jdk\jdk-17.0.9'
.\mvnw.cmd "-Dtest=PendingTerminationProbeTest" test
```

Tests run: 9, Failures: 0, Errors: 0, Skipped: 0。BUILD SUCCESS。

### 验收对照

| 验收要点 | 结论 |
| --- | --- |
| 先取得旧调用静止／退出证明再 CAS；fence／lease／epoch 不能单独授权 | 通过 |
| 独立终结证据；原 ToolUse／历史保留；匹配终结结果与等待 metadata 一致 | 通过 |
| 同键重试、旧 writer 竞争、保存失败、证明丢失不产生第二次状态变换；不明时仅核对原事实 | 通过 |
| 成功后全新实例独立读回；pending 不复活 | 通过 |
| 终结与重启读回的模型／工具／其他工具发送增量为 0 | 通过 |
| 仅唯一平台输入 pending；混合／多 pending 保持 RECONCILING 与 Session 阻断 | 通过 |

### 证据

- `backend/evidence/issue27/验收记录.md`
- `backend/evidence/issue27/probe-summary.json`

### 未纳入

正式取消／过期 API、动态表单页面、真实会议、`runtime_state_terminalization` 正式 DDL、阶段 0 整体通过。本条仅隔离验证，未执行正式 DDL／业务库接入／提交部署。
