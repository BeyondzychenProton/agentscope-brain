# Mission: AgentScope Workspace

## Why
为了在当前 AgentScope Java 数字员工平台的设计与接入中正确使用 Harness Workspace API，能读懂路径、运行上下文与文件后端如何共同决定一次读写落在哪里。

## Success looks like
- 能沿着 HarnessAgent.Builder → RuntimeContext → WorkspaceManager → AbstractFilesystem 读懂一次工作区访问。
- 能区分 Workspace 文件、AgentState 恢复状态与平台业务事实。
- 能根据共享范围选择 IsolationScope，并说出它不负责什么。

## Constraints
- 以官方 AgentScope Java v2.0.4 文档与源码为本课程基线；它是本仓库记录的调研基线，不代表已经完成项目集成。
- 用中文教学，先场景与运行流程，再映射 API、源码和版本边界。

## Out of scope
- AgentScope Python。
- 未固定版本的 API 行为。
- 本项目 Workspace 与记忆存储协议的完整实现设计。
