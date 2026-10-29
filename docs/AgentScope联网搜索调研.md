# AgentScope Java 联网搜索调研

调研日期：2026-10-29。项目 `backend/pom.xml` 固定 AgentScope Java 2.0.4，并包含 core、harness 与模型扩展；引入依赖并不证明应用已启用搜索。

## 调用机制

将搜索能力注册为工具，模型依据工具名称、描述和参数 schema 决定是否调用；Toolkit 分派调用，工具请求搜索服务，结果返回模型，模型再组织回答。[官方工具文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/building-blocks/tool.md) 展示 Java 工具及 MCP 接入；实际工具调用仍需模型支持 tool calling。

## 可选路径

1. Harness 内置 web_search：上游维护者确认该工具请求 Tavily，读取进程环境变量 `TAVILY_API_KEY`；缺少 key 时调用失败，`disableWebTools()` 可关闭内置 web 工具。该说明针对上游当时 main，2.0.4 的具体行为应在部署前依据对应 tag 源码或本地 jar 验证。[上游 issue 与维护者核对](https://github.com/agentscope-ai/agentscope-java/issues/3241)。
2. 自定义 Java 搜索工具：使用 `@Tool` / `@ToolParam` 包装选定供应商 API，注册到 Toolkit 并配置到 Agent；异步 I/O 可返回 `Mono`。由应用负责密钥、网络、超时、错误与结果规范。[工具文档](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/building-blocks/tool.md)。
3. MCP 搜索服务：使用 MCP 客户端连接搜索服务并注册 Toolkit，框架负责发现与分发。要独立确认供应商的部署、认证与服务质量。[工具文档](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/building-blocks/tool.md)。

建议先用真实网络与密钥做最小验证，再根据国内可达性、中文结果、成本和数据要求选供应商。统一返回标题、URL、摘要、发布时间（若有），让回答保留来源；限定查询长度、结果数、超时和错误信息。网页内容属于不可信外部数据，不应成为系统指令；若允许抓取 URL，还须限制内网地址及重定向。注意搜索查询会发往第三方，应避免发送敏感信息。

验证应覆盖：需要实时信息时实际发生工具调用，返回可打开的来源；无结果、超时和密钥缺失时明确失败；禁用工具时不可调用；并发用户隔离。上游 main 文档可能与固定版本有差异，集成前需编译并以 2.0.4 实测；本调研未调用真实搜索服务。
