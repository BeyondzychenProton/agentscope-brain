# 模型与会议 MCP 接入前提核验

> 核对日期：2026-10-23，Asia/Shanghai。对应研究议题：[核验模型与会议 MCP 的接入前提](https://github.com/BeyondzychenProton/agentscope-brain/issues/17)。本文完成资料调查和接入前提清单，不表示平台、模型或目标会议环境已经联通。
>
> 本次只查阅仓库资料、官方公开文档及官方 AgentScope Java `v2.0.4` 固定源码；未调用付费模型，未访问目标会议服务，未使用联调凭据，未预约或取消会议。本研究没有已登录的平台用户，不能宣称“平台登录人 → MCP 操作者”身份链成立。联调资料含测试凭据和个人信息，本文仅记录字段名、契约和规则，不复制具体值或内网目标。

## 1. 结论与证据等级

当前资料足以确定模型接入所需的配置、会议 MCP 的文档契约及后续验收路径，可以结束这项**资料研究**。仍缺精确模型配置、正式会议 Skill 包、目标服务部署记录，以及通过真实平台登录完成的闭环证据；这些是实施与联调输入，不应被写成已通过的研究结果。[一期总体设计](../../一期总体设计.md#8-后续输入与版本依据)

| 等级 | 本文如何使用 | 不能据此声称什么 |
| --- | --- | --- |
| 官方契约 | 阿里云能力文档、MCP `2025-03-26` 规范说明接口承诺与版本规则 | 当前账号有模型权限；指定部署已实现该版本 |
| 官方源码静态证据 | AgentScope Java `v2.0.4`，提交 `0b6743b263ba8e39eb417e35048026f1e0196548`，确认代码路径存在 | 本项目已集成、编译或运行通过；最新版本行为相同 |
| 项目已确认设计 | 可信登录人、固定员工发布版本、受控工具、一次性确认、素材和未知结果规则 | 对应 Java 服务与 Web 已实现 |
| 提供方文档声明 | 本地会议 MCP 联调说明描述工具、签名、权限及幂等行为 | 目标部署的 `tools/list` 和实际业务行为已经验证 |
| 目标环境实证 | 本研究没有采集 | 不填写“已联通”“身份校验通过”“预约成功”或“恢复成功” |

当前仓库的正式应用入口仍声明处于设计阶段；现有 SQL 验收只覆盖对应数据库结构、关系及 SQL 性质，不能替代应用登录、模型输入、Skill 装载、MCP 或重启恢复验收。[正式应用设计入口](../../正式应用设计/README.md)、[员工配置发布与授权建表规格的证据边界](../../数据库设计/数字员工配置与发布/一期员工配置发布授权建表规格.md#83-已交付文件与兼容范围)

## 2. 模型、图文与结构化输出

### 2.1 已知条件与缺口

| 项目 | 可确认的事实 | 接入前仍需补齐或实测 |
| --- | --- | --- |
| 提供方 | 一期使用阿里千问；当前未锁定具体模型 ID | 地域、业务空间、API 类型／Base URL、精确模型 ID、凭证引用及访问权限；不能只填“千问” |
| 文本与流式回复 | 阿里云公开 API 文档提供模型调用及工具调用流程 | 选定模型在实际 AgentScope Adapter 下的流式结束、错误、工具参数分片与超时行为 |
| 图片输入 | 官方视觉接口接受文字与图片内容块，支持 URL 或 Base64 等输入方式 | 实际模型支持范围、图像格式／数量／字节和像素限制，以及私有原件到模型请求的转换链路 |
| 工具调用 | Function Calling 返回工具名与参数，由应用执行工具并回传结果 | 所选模型、思考模式与参数组合是否可靠支持工具调用；工具名／参数必须经过平台校验 |
| JSON 输出 | 在受支持的模型／模式下，`json_object` 保证 JSON 语法，不保证业务 Schema；`json_schema` 支持范围更窄 | 锁定模型及模式；验证缺字段、额外字段、错误类型、截断、拒答与失败回退 |
| 动态表单 | 项目已确认模型可提出结构，服务端校验、前端受控渲染 | 表单协议、组件／动作范围、版本、修复次数及失败展示由对应设计议题收敛；不由模型能力声明替代 |

来源：[一期总体设计](../../一期总体设计.md)、[阿里云 API Key 与地域规则](https://help.aliyun.cn/zh/model-studio/get-api-key)、[图像与视频理解](https://help.aliyun.com/zh/model-studio/vision)、[Function Calling](https://help.aliyun.cn/zh/model-studio/qwen-function-calling)、[结构化输出](https://help.aliyun.com/zh/model-studio/qwen-structured-output)。阿里云各地域的接入点、API Key 和模型列表分别管理，凭证存在不等于目标地域／业务空间有权调用所选模型。

**图文与严格结构不能合并成一个未经核验的承诺。**本次读取的阿里云结构化输出文档明确说明：多模态输入时，`json_schema` 会降级为 `json_object`，Schema 约束不生效。因此正式表单仍必须由服务端校验；若考虑“先提取图像信息，再进行文本结构化”的多步路径，应作为后续方案比较，不能当成已决定的架构。[官方结构化输出说明](https://help.aliyun.com/zh/model-studio/qwen-structured-output#%E8%8E%B7%E5%8F%96%E6%8C%87%E5%AE%9A%E6%A0%BC%E5%BC%8F%E7%9A%84%E8%BE%93%E5%87%BA)

图像接口允许的本地文件路径上传属于特定 SDK 的上传能力；OpenAI 兼容 HTTP 不能据此直接读取服务器磁盘路径。平台需从有权限的私有素材引用生成适用于所选接口的内容块，不能把浏览器临时 URL、磁盘路径或过期下载地址直接当成可恢复的模型输入。[官方图像上传方式](https://help.aliyun.com/zh/model-studio/vision)、[通用素材设计](../../数据库设计/会话运行与消息/通用素材与工具操作设计.md#2-消息素材与私有对象存储)

### 2.2 AgentScope 调用链的静态核对

以下仅针对固定源码，不构成本项目选型或运行验收：

1. `ReActAgent` 的结构化调用依据模型的 `supportsNativeStructuredOutput()`／`supportsNativeStructuredOutputWithTools()` 选择原生路径；原生路径失败可回退到 `generate_response` 合成工具。因此“拿到结构化结果”可能走不同路径，应记录实际使用的机制。[选择与回退](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java#L1250-L1298)
2. DashScope Adapter 的 `DashScopeToolsHelper` 把 `GenerateOptions.responseFormat` 放入 `DashScopeParameters`，DTO 将其序列化为 `response_format`；`DashScopeHttpClient` 自行构造 HTTP 请求。阿里云页面对其官方 DashScope Java SDK 的限制，不能直接外推为这条 AgentScope HTTP 实现也不支持。[参数映射](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/formatter/DashScopeToolsHelper.java#L108-L112)、[请求字段](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/dto/DashScopeParameters.java#L110-L112)、[HTTP 发送](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/DashScopeHttpClient.java#L204-L233)
3. OpenAI 兼容 Adapter 也有 `responseFormat` 请求映射；其是否适合所选千问接口仍需实测。DashScope Adapter 在 `enableThinking=true` 时报告不支持原生结构化输出；这进一步说明必须固定具体 Adapter 与思考模式，不能仅依据方法名或型号系列作保证。[兼容请求映射](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai/src/main/java/io/agentscope/extensions/model/openai/formatter/OpenAIChatFormatter.java#L158-L162)、[思考模式判断](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/src/main/java/io/agentscope/extensions/model/dashscope/DashScopeChatModel.java#L432-L437)

后续模型接入记录建议固定：员工发布版本、AgentScope 版本、Adapter／接口类型、模型 ID 与是否使用动态别名、地域及业务空间引用、思考模式、输入模态、工具调用参数、结构化输出机制、超时／重试策略和安全日志配置。这是为使验收可复现而列出的清单，不决定具体模型或技术路径；其中发布版本、模型及凭证引用的既有约束继续遵守。[员工发布配置要求](../../数据库设计/数字员工配置与发布/一期员工配置发布授权建表规格.md#4-配置结构与文本包的具体提案)

## 3. 会议 Skill 的输入状态与装载前提

已确认：会议 Skill 组织查询、选人、预约与核验的方法；一期只接纳受控文本入口及参考资料，不开放任意脚本、命令或容器执行。正式正文和资源固定在员工发布版本中，通过只读 `AgentSkillRepository` 装载，不能被可变 Workspace 或同名“最新版”覆盖。[一期总体设计](../../一期总体设计.md#5-skills工具与写操作确认)、[固定 Skill 版本方案](../../数据库设计/数字员工配置与发布/Skills_MySQL管理与版本装载设计.md)

框架接口允许平台实现版本限定的 Repository；但它不自动完成平台的发布事务、权限检查和不可变快照。既有会议 Skill 片段是示例，资料仍明确正式业务 Skill 由业务方后续提供，不能把示例当成已接收并验收的正式包。[官方 Repository 接口](https://github.com/agentscope-ai/agentscope-java/blob/0b6743b263ba8e39eb417e35048026f1e0196548/agentscope-core/src/main/java/io/agentscope/core/skill/repository/AgentSkillRepository.java)、[现有 Skills 调研](../Skills/AgentScope%20Java%20Skills存储与定义配置调研.md)、[后续输入](../../一期总体设计.md#8-后续输入与版本依据)

正式包的既有要求包括 `name`、`description`、完整正文、受控参考资源及其相对路径。建议业务方补充适用的工具契约版本、流程中的人员消歧／表单收集／写前确认／结果核验位置，以及不可完成时如何返回，供后续议题确认。发布与验收继续核对清单和摘要、中文资料读取、旧新版本隔离、缺资源／坏摘要拒绝及重启后的相同版本装载。Skill 不能携带凭据，也不能授予工具权限或替代用户确认。[Skill 内容与验收规则](../../数据库设计/数字员工配置与发布/Skills_MySQL管理与版本装载设计.md#3-完整-skill-包)

## 4. 会议 MCP：文档声明的契约

本节唯一业务契约来源为[会议 MCP 联调说明（受限本地资料）](<../../对接文档/MCP身份查询联调说明 (1).md>)；下列内容均为**提供方文档声明，尚无目标环境实证**。不应将原始资料、个人候选列表、HTTP 原始报文或凭据附到公开议题。

### 4.1 传输、鉴权与可信操作者

| 项目 | 文档声明／已确认的平台规则 | 实施与验收条件 |
| --- | --- | --- |
| 协议 | MCP Streamable HTTP，JSON-RPC 2.0；联调基线 `2025-03-26` | 记录目标部署版本及初始化实际协商版本；不直接套用其他版本的传输行为 |
| 顺序 | `initialize` → `notifications/initialized` → `tools/list` → `tools/call` | 检查客户端完成初始化、能力协商和工具分页；按返回内容类型处理 JSON 或 SSE |
| 每请求鉴权 | `X-MCP-App-Key`、`X-MCP-Timestamp`、`X-MCP-User-Phone`、`X-MCP-Signature` | 初始化、通知、发现和实际调用均重新按请求构造头；不能只在首次连接签名 |
| 签名算法 | `lowercase_hex(HMAC-SHA256(secret, UTF-8(timestamp + "\n" + userPhone)))`；其中换行是 LF，时间戳为毫秒，允许时差不超过五分钟 | 受控凭证注入、时钟同步、编码和大小写测试；测试记录不打印秘密、签名或完整手机号 |
| 操作者来源 | 服务端从已认证平台会话取得 `UserId`，读取其当前绑定手机号；模型和浏览器参数不得替换操作者 | 需要真实平台登录、可信名册核对及有效身份修订；手填手机号发请求不能证明该链成立 |
| 查询对象 | `phones`／`attendeePhones` 等业务参数选对象，不改变请求操作者 | 两名用户分别运行，验证不能通过参数切换为另一人身份 |
| 分层判权 | 平台检查账号、员工授权、固定版本与工具策略；会议服务检查身份、业务权限及目标资源规则 | 平台管理员身份不能绕过会议权限和当前用户确认；新工具不因被发现就自动获准使用 |
| 错误分层 | HTTP `401` 为验签或用户身份失败；`503` 可表示服务端缺验签配置；工具 `FORBIDDEN` 等是业务结果 | 分开记录 HTTP、JSON-RPC、MCP `isError` 与业务状态；`200` 不能代替业务成功 |

HMAC 公式只证明请求方持有共享秘密并绑定了签名中的时间与手机号；它本身不证明手机号归属当前平台登录人，也不证明本人已确认这笔写操作。平台的可信登录链、不可变操作提案及一次性确认必须另外成立。[一期身份与 MCP 规则](../../一期总体设计.md#4-登录身份与会议-mcp)、[通用操作确认](../../数据库设计/会话运行与消息/通用素材与工具操作设计.md#4-独立的一次性操作确认)

MCP 官方 `2025-03-26` 规范支持上述初始化、版本协商、JSON／SSE 响应和 `tools/list` 分页；**自定义 `X-MCP-*` HMAC 是会议提供方契约，不是 MCP 标准身份协议**。规范中的工具注解也不能替代平台受控策略。[官方 Lifecycle](https://modelcontextprotocol.io/specification/2025-03-26/basic/lifecycle)、[官方 Transports](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)、[官方 Tools](https://modelcontextprotocol.io/specification/2025-03-26/server/tools)

### 4.2 工具契约与业务边界

| 工具 | 文档声明的用途／边界 | 文档声明的额外业务权限 |
| --- | --- | --- |
| `identity.resolve` | 按人员条件找候选；精确唯一与模糊匹配要区分。模糊匹配即使只有一人仍需用户确认 | 无额外权限，仍须通过请求鉴权 |
| `department.search` | 部门／岗位级联树，用于缩小人员查询范围 | 同上 |
| `calendar.get_freebusy` | 查询指定人员有效日程的忙区间；无记录不等于承诺本人一定可参会 | 同上 |
| `calendar.find_slots` | 返回工作日和已配置工作时段内、满足时长的共同空闲区间；不是会议室锁 | 同上 |
| `calendar.verify` | 按 `meetingId`、原参会人集合及预期起止时间核验预约；集合或时间不符不能标成功 | `administration:chamberAppt:query` |
| `room.search` | 查询未来可预约会议室；当前 `equipment` 不参与过滤，查询结果不锁资源 | `administration:chamber:list` |
| `room.reserve` | 确认后创建预约；发起人来自鉴权身份，只有显式列入 `attendeePhones` 才是参会人 | `administration:chamberAppt:add` |
| `meeting.list_mine` | 当前用户发起或参加、尚未开始且有效的会议；不是完整历史台账 | 无额外权限，仍须通过请求鉴权 |
| `meeting.cancel` | 确认后由发起人取消未开始的会议；展示的 `canCancelMeeting` 不替代真实业务权限检查 | `administration:chamberAppt:edit` |

需在实际 `tools/list` 中核对工具是否存在、描述、完整 `inputSchema`、分页、必填字段及参数限制，并固化为工具绑定版本。联调说明明确新增工具及手机号鉴权需要对应服务端版本部署后才生效；文档日期、工具名出现在清单中，都不能代替部署记录及实际权限验证。[固定工具绑定规格](../../数据库设计/数字员工配置与发布/一期员工配置发布授权建表规格.md#34-tool_binding_revision不可变工具调用语义)

参数联调还需覆盖：会议服务时区和 `yyyy-MM-dd HH:mm:ss` 格式、左闭右开时间区间、时长最小值／步长、人数及参会人列表限制、工作日数据和工作时段配置。会议室查询后的占用竞争以预约事务最终结果为准；预约成功后，`calendar.verify` 使用原参会人列表，不擅自加入发起人。核验是系统检查，不需要第二次授权。[一期确认与核验规则](../../一期总体设计.md#5-skills工具与写操作确认)

## 5. 幂等、确认与未知结果

会议文档声明两个写工具要求 `requestId` 为 8～64 位英文字母、数字、下划线或横线；同一用户、同一工具、同一请求号在 24 小时内由 Redis 去重。同号同参数可返回原结果，处理中为 `IN_PROGRESS`，同号异参为 `IDEMPOTENCY_CONFLICT`。**这些仍是提供方声明，不是本研究验证过的部署行为。**

| 情况 | 已确认的处理边界 | 真实验收需要证明 |
| --- | --- | --- |
| 写前用户确认 | 在平台持久化具体不可变提案；确认绑定当前操作者、原 Run、工具绑定、身份修订和参数 | 只提交意图／填写表单不能提前发送；跨用户、旧版本、改号、过期或改参数确认均被拒绝 |
| 安全网络重试 | 相同逻辑操作保持原 `requestId` 与参数；记录实际传给提供方的请求键 | 双击确认和重复 HTTP 请求只产生一次逻辑操作及一个外部业务效果 |
| `IN_PROGRESS` 或响应不明 | 停止自动盲重试，不更换请求号；进入核对 | 上游已写但响应丢失、发出后进程退出等场景不会重复预约／取消 |
| 已知 `meetingId` | 在当前权限下按资源核验，并区分“创建回执”和“核验一致” | 必要参会人／时间匹配；核验失败不能包装为已验证成功 |
| 未获资源号的未知结果 | 当前资料未声明可按 `requestId` 查询原操作的独立工具，不能推断具备这一能力 | 无确定核对途径时转人工核对；不能仅用“未来会议列表未找到”判定从未写入 |
| 超过服务幂等窗口 | 不从本地稳定请求号推出上游永久去重 | 平台恢复策略不能在 24 小时后自动重放未知写操作 |

平台还需在发送前持久化发送意图、领取单一执行者，并把不确定结果保留为 `UNKNOWN`，保持 Run 的 `RECONCILING` 及会话占用，直到取得确定证据。HTTP 超时、本地取消、权限撤销或进程重启都不能证明远端没有写入；本地 SQL 唯一键也不提供远端去重能力。[通用工具操作设计](../../数据库设计/会话运行与消息/通用素材与工具操作设计.md#3-通用工具操作)

## 6. 后续需要的输入

| 输入／证据 | 当前状态 | 用于什么验收 |
| --- | --- | --- |
| 精确模型与接口配置清单 | 缺；总体设计只确认提供方 | 文本、图片、工具循环、结构化结果和错误路径 |
| 模型凭证引用及权限 | 本研究不读取具体值；未验证目标账号权限 | 指定地域／业务空间真实可调用；不将密钥放入发布 JSON、模型上下文或日志 |
| 正式会议 Skill 完整文本包 | 待业务方补充；现有片段仅作示意 | 完整内容装载、业务顺序及版本隔离 |
| 会议部署版本与工具发现快照 | 缺目标部署实证 | 身份签名和全部所需工具契约是否实际生效 |
| 可信普通平台用户与会议账号开通证据 | 平台链尚未实现／验收；无已登录研究身份 | 名册核对、真实登录、首次改密、有效手机号修订到 MCP 的完整链 |
| 目标会议权限与基础配置 | 文档列出要求，目标未核验 | 查询／预约／核验／取消权限；工作日、工作时段、时区、Redis 幂等配置 |
| 真实应用 Adapter 与持久化恢复 | 当前为已确认设计和 DDL 证据 | 操作确认、发送领取、结果分层、`UNKNOWN` 核对及重启不重放 |

这些输入不要求重新讨论已确认的身份、固定发布版本或写前确认规则；它们用于把既定规则落到可验证的目标环境。[当前正式应用范围](../../正式应用设计/README.md)、[一期总体设计](../../一期总体设计.md)

## 7. 不含凭据的后续验收步骤

以下是后续实施验收清单，**本研究未执行**。真实会议写操作应在另行授权的指定测试环境中进行；测试数据和凭证通过受控配置注入，公开结果只保留脱敏摘要及必要关联标识。

1. **固定清单。**保存平台构建版本、AgentScope／Adapter 版本、员工发布版本、模型配置摘要、Skill 包摘要、工具绑定版本、目标会议部署版本及时间。凭证仅登记引用，不复制具体值。
2. **先验模型能力。**用无个人信息的合成文字与合成图片分别验证文本、图文、工具调用循环和结构化输出；记录实际请求参数、输出校验结论与失败路径，不保存密钥或含秘密的完整请求。组合验证“图文 + 工具 + 结构化”，不能以各单项通过代替组合通过。
3. **验证素材与 Skill。**从真实平台上传合成图片并验证私有原件、元数据、模型转换和重启后重建引用；分别装载 Skill V1/V2，核对正文与资源摘要、旧会话固定版本和旁路覆盖拒绝。
4. **建立可信登录链。**通过受控流程开通两名普通用户，核对名册／手机号归属，真实登录并完成首次改密。服务端从会话解析 `UserId` 与当前绑定手机号；证明模型或浏览器指定另一号码不能切换 MCP 操作者。此步前只能做签名组件测试，不能声称端到端身份链通过。
5. **只读协议与部署核对。**用当前用户完成带鉴权的初始化、初始化通知及完整分页工具发现，记录协商协议、`serverInfo`、部署标识、工具名与 Schema 摘要。验证 JSON／SSE 两种实际响应路径及鉴权失败与工具业务失败的区分；不把 `tools/list` 成功当作本人归属或写权限证明。
6. **只读业务闭环。**依次测试人员候选／模糊唯一确认、部门筛选、忙闲与时间段、会议室查询及我的会议；验证时区、工作时段、权限不足及数据不可用。不得承诺设备精确过滤或全部历史会议。
7. **授权后的真实写闭环。**展示具体预约提案，取得当前用户一次性确认后才发出预约；保存结构化回执，按原参会人和时间核验。另建取消提案并确认后取消测试会议，再核对业务状态。分别报告“提供方创建／取消回执”“核验结果”“平台持久化／Web 展示”，不能合并成一个成功指标。
8. **幂等与故障。**用受控桩或故障注入先验证同号同参、同号异参、双击、`IN_PROGRESS`、响应丢失、已发出后重启、改号／撤权、超过服务窗口；目标实证中只运行提供方与用户授权的测试案例。未知结果不能换号自动重发；缺少按请求号核对能力时保留人工核对入口和待核对状态。
9. **归档证据。**形成可重复的脱敏用例、版本指纹、预期／实际结果、数据库操作状态和业务核验摘要；对失败项列出具体缺口。身份、模型、会议业务与恢复分别下结论，不用数据库结构验收或一次 HTTP 成功覆盖其他部分。

## 8. 可用于研究议题 resolution 的公开安全要点

- 已完成现有资料与官方来源核验：会议 MCP 的传输、逐请求 HMAC、操作者来源、工具权限、写前确认、幂等及未知结果规则可形成接入清单；均未冒充目标部署实证。
- 千问具体模型、地域／业务空间、Adapter、思考模式和输出机制仍需固定；官方文档说明多模态输入的 `json_schema` 会降级，正式动态结构必须经服务端校验。AgentScope 的参数下发存在静态源码证据，仍需所选模型实测。
- 正式会议 Skill 包、目标部署与 `tools/list` 快照、真实平台登录到 MCP 操作者的链路及真实查询／预约／核验／取消闭环尚未验收。研究没有使用测试凭据或执行目标业务请求。
- 可将本研究议题按“调查完成、接入前提及验收清单已明确”关闭；后续设计／实施议题继续承接具体输入和真实验收，不标记模型、身份链或会议闭环已完成。
