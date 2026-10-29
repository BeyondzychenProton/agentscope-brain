# 正式文本工作台（Issue #46）

React 与 CopilotKit Runtime 是浏览器入口；Runtime 经本机 Java 服务读取平台会话并执行文本对话。浏览器与 Runtime、线程接口使用同一站点；Java 服务不应直接暴露给浏览器。旧 `frontend/` 与 `experiments/agui-copilotkit/` 不是此入口。

项目固定使用 Node 24.22.1 和 npm 11.19.1。在仓库根目录安装：

```powershell
Set-Location 'D:\code project\agentscope\formal-web'
$env:Path = 'D:\code project\agentscope\.codex-local\issue-34\runtime\node-v24.22.1-win-x64;' + $env:Path
npm ci --ignore-scripts --no-audit --no-fund
```

Java 配置在本机忽略的 `backend/config/application.yaml` 中启用 `brain.copilot.enabled` 并填写至少 32 字符的 `brain.copilot.runtime-secret`。启动本应用前，把同一个值写入进程环境变量 `BRAIN_COPILOT_RUNTIME_SECRET`；这个服务间密钥只由服务端使用，不是用户登录令牌，不得出现在浏览器配置或记录中。`BRAIN_JAVA_ORIGIN` 默认为 `http://127.0.0.1:8080`，应指向不可从外部直接访问的 Java 服务。生产同源 HTTPS 入口与反向代理需单独配置。

线程结构的唯一 DDL 在 `db/schema/13_Copilot文本线程建表.sql`，结构化展示增量迁移在 `db/schema/14_Copilot结构化展示迁移.sql`。新库按顺序执行；已有 schema 13 的库只执行一次 schema 14。应用不会自动建表或迁移；缺迁移时就绪检查拒绝启动使用。迁移增加可空的 `display_json`，保留旧正文；新回复的消息、工具请求和结果使用稳定标识持久化，刷新从同一展示数据恢复。已有业务会话、正式消息和投影表不作为新线程存储。

2026-10-29 的修复按用户要求增加了员工发布版本中已有的只读工具。每轮 Agent 使用固定发布模型，原生工具循环执行完后继续流式回答，不使用第二个非流式补救模型。模型正文工具标签的兼容仅位于模型适配层，且只允许本次已发布、已注册的工具；无法解析、未发布或截断的调用明确失败。前端只从 AG-UI 工具事件展示过程，旧正文清理不生成工具执行状态。reasoning 现在通过独立思考区展示：仅呈现模型返回的 reasoning 原文，180px 固定高度并滚动；正文消息开始后自动折叠，支持手动展开。思考内容保存在既有 display_json 中，不进入后续模型的正文历史，没有 reasoning 的回复不显示空框。

验证入口：在 backend 执行 `mvnw.cmd -B -ntp -Dbrain.build.directory=target/tool-stream-fix verify`，在本目录执行 `npm run typecheck`、`npm run build` 和 `node --test app/assistant-content.test.ts`。仓库根运行 `C:\Python314\python.exe db/tests/执行Copilot文本隔离验收.py --tools --jar backend/target/tool-stream-fix/agentscope-brain-backend-0.1.0-SNAPSHOT.jar` 验证已登录浏览器、Runtime、Java、模型与签名 MCP 的完整只读工具链路；省略 `--tools` 验证原文本场景。脚本只创建回环隔离 MySQL 并使用合成模型/MCP，不读取共用凭据。Playwright 目录中的原生扩展使用 Python 3.14；不要用不匹配的 Python 解释器。

真实模型、真实 MCP 及部署环境需单独验收。构建或替身验证不能代替阶段验收。

思考展示浏览器验收：在仓库根执行 `C:\Python314\python.exe db/tests/执行Copilot文本隔离验收.py --reasoning --jar backend/target/tool-stream-fix/agentscope-brain-backend-0.1.0-SNAPSHOT.jar`。该模式用合成模型分段发送 reasoning，再发送正文，验证原文、固定高度、自动折叠、手动展开和刷新恢复。
