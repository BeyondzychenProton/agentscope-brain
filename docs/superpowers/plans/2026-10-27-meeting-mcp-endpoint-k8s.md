# 会议 MCP 测试环境切到 cms-test-k8s — 实现计划

> **For agentic workers:** 本任务为私有配置同步；无纯逻辑可测代码。步骤按序执行；每步完成后勾选。

**Goal:** 按 `docs/superpowers/specs/2026-10-27-meeting-mcp-endpoint-k8s-design.md`，将会议 MCP endpoint 同步改为 `https://cms-test-k8s.shsets.com/stage-api/mcp`，并保持 catalog `sha256` 与 YAML 一致。

**Architecture:** 仅改本机私有文件 `backend/config/application.yaml` 与 `backend/config/release-catalog.private.json`（Git 忽略）。`sha256` = SHA-256(hex) of Jackson `canonical(content)`（键排序、无多余空格的 JSON 字节）。

**Tech Stack:** 本地 YAML／JSON；Python 或等价工具重算 digest。

**Spec:** `docs/superpowers/specs/2026-10-27-meeting-mcp-endpoint-k8s-design.md`

---

## Task 1: 校验 digest 算法并对齐两处 endpoint

**Files:**
- Modify: `backend/config/application.yaml`
- Modify: `backend/config/release-catalog.private.json`

- [x] **Step 1: 用旧 content 校验 sha256 算法**

对旧 `connection/meeting-mcp/v1.content` 做键排序 JSON（`separators=(',', ':')`）再 SHA-256，应得到现有 `0346f08df2a0a3b27409f65ed7e948d39df4f0642d7e8e9cf417711b690d20e4`。不一致则改用与 Java `ObjectMapper.writeValueAsBytes(TreeMap…)` 等价的序列化后再算。

- [x] **Step 2: 更新 YAML**

将 `brain.resources.meeting-mcp.endpoint` 改为：

```yaml
endpoint: "https://cms-test-k8s.shsets.com/stage-api/mcp"
```

其余 `transport`／`protocol-version`／`app-key`／`secret` 不动。

- [x] **Step 3: 更新 catalog 并重算 sha256**

`connection/meeting-mcp/v1.content.endpoint` 改为同一新地址；用 Step 1 同一算法重算 `sha256` 并写回该条目。其它 catalog 条目不动。

- [x] **Step 4: 自检**

1. 两处 endpoint 字符串完全一致且为新地址。  
2. 新 content 的 digest 等于文件中的 `sha256`。  
3. `git status` 不出现这两份私有文件（仍被忽略）；不提交它们。  
4. 提醒用户重启后端使配置生效。

---

## 完成标准

- 私有 YAML 与 catalog 均指向 `https://cms-test-k8s.shsets.com/stage-api/mcp`
- catalog 该条目 digest 自洽
- 鉴权与协议字段未改；公开仓库无凭据泄露
