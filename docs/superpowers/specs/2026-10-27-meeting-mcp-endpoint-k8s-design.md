# 会议 MCP 测试环境地址切换（cms-test-k8s）

日期：2026-10-27。会议助手 MCP 主机从 `cms-test.shsets.com` 迁到 `cms-test-k8s.shsets.com`；路径、鉴权与协议不变。

## 目标

本机正式后端能连上新的会议 MCP 测试环境，避免旧主机导致访问失败；启动 readiness 中 catalog 与 YAML 保持一致。

## 新地址

| 项 | 值 |
| --- | --- |
| 新 endpoint | `https://cms-test-k8s.shsets.com/stage-api/mcp` |
| 旧 endpoint | `https://cms-test.shsets.com/stage-api/mcp` |
| 不变 | transport、protocol-version、app-key、secret、工具绑定、Skill、DB 预置 |

## 做法

同步修改两处**本机私有**运行配置（Git 忽略、不打包）：

1. `backend/config/application.yaml`  
   `brain.resources.meeting-mcp.endpoint` → 新地址。
2. `backend/config/release-catalog.private.json`  
   `connection/meeting-mcp/v1.content.endpoint` → 新地址；  
   按现有 `canonical(content)`（键排序 JSON）重算该条目 `sha256`，避免 `REFERENCE_CATALOG_DIGEST` / `MCP_CONFIG_MISMATCH`。

改完后重启后端进程使配置生效。不探测或改写数据库中的发布摘要。

## 非目标

- 不改对接文档、历史 `verification/` 探针与验收记录  
- 不改线上凭据、协议、工具契约或会议 Skill  
- 不提交私有 YAML／catalog（仍保持 Git 忽略）

## 验收

1. YAML 与 catalog 中 MCP endpoint 均为新地址，且该 catalog 条目 `sha256` 与 `canonical(content)` 一致。  
2. 后端重启后不再因旧主机连接失败；readiness 不因 MCP 配置不一致拒绝启动。  
3. app-key／secret／protocol 等其余会议 MCP 字段未变。
