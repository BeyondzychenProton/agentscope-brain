# 正式文本工作台（Issue #46）

React 与 CopilotKit Runtime 是浏览器入口；Runtime 经本机 Java 服务读取平台会话并执行文本对话。浏览器与 Runtime、线程接口使用同一站点；Java 服务不应直接暴露给浏览器。旧 `frontend/` 与 `experiments/agui-copilotkit/` 不是此入口。

项目固定使用 Node 24.22.1 和 npm 11.19.1。在仓库根目录安装：

```powershell
Set-Location 'D:\code project\agentscope\formal-web'
$env:Path = 'D:\code project\agentscope\.codex-local\issue-34\runtime\node-v24.22.1-win-x64;' + $env:Path
npm ci --ignore-scripts --no-audit --no-fund
```

Java 配置在本机忽略的 `backend/config/application.yaml` 中启用 `brain.copilot.enabled` 并填写至少 32 字符的 `brain.copilot.runtime-secret`。启动本应用前，把同一个值写入进程环境变量 `BRAIN_COPILOT_RUNTIME_SECRET`；这个服务间密钥只由服务端使用，不是用户登录令牌，不得出现在浏览器配置或记录中。`BRAIN_JAVA_ORIGIN` 默认为 `http://127.0.0.1:8080`，应指向不可从外部直接访问的 Java 服务。生产同源 HTTPS 入口与反向代理需单独配置。

线程结构的唯一 DDL 在 `db/schema/13_Copilot文本线程建表.sql`；应用不会自动建表。安装结构与更新就绪基线后，才可启用此功能。已有业务会话、正式消息和投影表不作为新线程存储。

目前本实现仍需完成浏览器端到端验证、隔离数据库验证和真实模型增量证据；未完成前不应作为上线入口。
