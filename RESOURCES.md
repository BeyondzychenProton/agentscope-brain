# AgentScope Workspace Resources

## Knowledge

- [AgentScope Java v2.0.4：Workspace 文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/workspace.md)
  官方主文档。用于目录结构、Builder 配置、逻辑路径、读取与写入、多用户覆盖和 Workspace/AgentState 边界。
- [AgentScope Java v2.0.4：Filesystem 文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/filesystem.md)
  用于理解 Local、Remote、Sandbox 后端以及 IsolationScope 如何映射到命名空间。
- [HarnessAgent.java](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java)
  固定标签源码。用于定位 Builder、getWorkspaceManager() 和按请求创建 workspaceFor(userId, sessionId) 的入口。
- [WorkspaceManager.java](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/workspace/WorkspaceManager.java)
  固定标签源码。用于追踪 Workspace 相对路径、覆盖读取和文件系统写入。
- [IsolationScope.java](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/IsolationScope.java)
  固定标签源码。用于核实 USER、SESSION、AGENT、GLOBAL 的键与缺省回退行为。
- [v2.0.4 Release](https://github.com/agentscope-ai/agentscope-java/releases/tag/v2.0.4)
  用于核对本课程固定的发布标签与版本背景。

## Project context

- [AgentScope 状态、记忆与工作区持久化调研](docs/调研/状态与持久化/AgentScope状态记忆工作区持久化.md)
  本仓库对 v2.0.4 文档和源码的静态核对，以及平台侧边界说明；文档明确标注未完成项目集成验收。
- [状态、记忆与工作区隔离存储设计规格](docs/数据库设计/状态记忆与工作区/状态记忆与工作区隔离存储设计规格.md)
  本项目已经确认的业务约束和隔离设计方向。它是项目设计，不是 AgentScope API 契约。
- [一期总体设计](docs/一期总体设计.md)
  用于理解本课程的会议室数字员工场景与平台责任边界。

## Gaps

- 本仓库没有 AgentScope Maven 依赖或可运行 Harness 集成；源码与文档核对不能证明本项目的运行时隔离、恢复或存储已通过验收。
