# Issue #9 实体与约束评审及长期记忆控制建表规格（评审稿）

> 当前回归补充：本页第 7 章保留本范围早期验收及指纹；2026-10-22 员工八表加入后的 7／17／21／29 表隔离结果见第 9 章及[全库验收记录](../数字员工配置与发布/员工配置发布授权与全库建表验收记录.md)。

> 2026-10-21（上海时间）：对应 [Issue #9](https://github.com/BeyondzychenProton/agentscope-brain/issues/9)。用户通过 grilling 逐项确认本文的 Q1～Q35 决定，并同意按关注点分组产出单一综合文档。2026-10-22 续评审（编号 C1～C24，见第 1 章第二张表）补齐了框架取证后的路径模板、既有表扩展、会话运行资料表 DDL、巡检与并发验收。本文只交付设计与评审用 DDL；框架 JDBC KV 往返、两阶段激活、任务恢复和授权并发都**没有**应用集成验收，不能据本文宣称已实现。远端 Issue 状态须单独同步，不能由本文推断已更新或关闭。
>
> 输入：[#7 状态、记忆与工作区隔离存储设计规格](状态记忆与工作区隔离存储设计规格.md)（用户可见规则与逻辑实体，仍以该文为准）、[身份建表 SQL](../../../db/schema/01_用户与管理员建表.sql)。输出：[会话运行消息建表 SQL](../../../db/schema/02_会话运行消息建表.sql)（C9、C10：并入 `business_message`、`business_run`、`conversation_audit_event` 的记忆列，空库建表，**已有数据的库需自行回填**）、[记忆控制建表 SQL](../../../db/schema/03_记忆控制建表.sql)（`memory_profile`、`memory_update_job`）、[会话运行资料建表 SQL](../../../db/schema/04_会话运行资料建表.sql)（`workspace_entry`、`workspace_snapshot_reference`）。共 21 张表。

## 1. 范围与已确认决策

| 编号 | 主题 | 确认的决定 |
| --- | --- | --- |
| Q6 | 框架 KV 表 | `agentscope_store`（名称可配置）由框架依锁定版本自动初始化；本项目 DDL 不创建、不修改它，只审查平台如何映射到它。 |
| Q7 | 平台控制记录 | 逻辑实体转为具体 DDL，新增第三个 SQL 文件。 |
| Q8 | 既有表 | 不修改已验收的表；新表与既有表的约束保持一致。 |
| Q9 | 一致激活 | 同时覆盖“手工保存/清空时正文、控制记录与审计一致生效”和“换代时旧任务失效、新代生效”。 |
| Q10 | 文档结构 | 按关注点分四章：框架 KV 映射、平台控制记录、字段约束、一致激活。 |
| Q11 | 代次类型 | `BIGINT UNSIGNED`，从 1 起，每次手工保存/清空加一。 |
| Q12 | 修订映射 | `memory_profile` 存 `document_revision`，并记录观察到的框架 KV 版本，平台自己维护计数并做条件更新。 |
| Q13 | 输入顺序 | `business_message` 增加全局 `input_sequence`，按用户+员工分配；本轮不改已有 SQL。 |
| Q14 | 任务幂等 | `UNIQUE (source_run_id, generation, task_type)`；任务类型为提取 `FLUSH` 与合并 `CONSOLIDATE`。 |
| Q15/Q26 | KV 路径 | 路径风格，固定模板，见 2.2 节。 |
| Q16/Q20 | 会话运行资料 | `workspace_entry` 只作索引，内容权威在框架 KV（文本）或私有对象存储（文件），用 `storage_type` 区分。 |
| Q17/Q22 | 既有表扩展 | `business_message`、`business_run` 的新增字段只在文档说明，暂不改 SQL。 |
| Q18/Q30 | 一致提交 | 两阶段：先把新代次内容写到新代次的 KV 路径（控制记录未激活前读不到），再在一个 SQL 事务中激活控制记录并追加审计。 |
| Q19 | 任务状态 | `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`、`STALE`、`CANCELLED`。 |
| Q21 | 快照引用 | 一期不实施触发入口，只设计表结构。 |
| Q23 | 审计 | 扩展 `conversation_audit_event`，不新建记忆审计表。 |
| Q24 | 授权再授予 | 任务保存创建时的 `platform_user.authorization_version`。 |
| Q25 | 旧任务清理 | 激活新代次的同一事务内批量置 `STALE`。 |
| Q27 | 重试 | 区分错误类型：瞬时错误重试，逻辑错误立即终态。 |
| Q28 | 异常状态 | `memory_profile.profile_status` 独立于 Session 的 `recovery_status`。 |
| Q29 | 条件更新 | 直接以 `generation` 与 `document_revision` 作条件，不另设 `row_version`。 |
| Q31 | 活跃代次 | 每个（用户，员工）一行，只保存当前代次；历史代次靠审计追溯。 |
| Q32 | 日记命名 | 按日期分文件，内容含来源 Run 标识用于去重。 |
| Q33 | 序号分配 | 在 `memory_profile.last_input_sequence` 上行锁加一。 |
| Q34 | DDL 范围 | 本轮只交付 `memory_profile`、`memory_update_job`；`workspace_*` 表只在本文设计，DDL 留待后续。 |
| Q35 | 评审清单 | 约束覆盖、跨表维护责任、恢复场景三份清单全部提供，见第 6 章。 |

**续评审决定（2026-10-22，C1～C24，均经用户逐项确认；Q17/Q22/Q34 的“暂不改 SQL、暂不出 DDL”由 C2、C8 取代）**

| 编号 | 主题 | 确认的决定 |
| --- | --- | --- |
| C1、C2 | 范围与落地 | 顺序为框架取证 → 既有表扩展与资料表 DDL 合并一次重新验收 → 参数写入文档；直接修改 `02` 号文件，不追加 ALTER。期间不 commit、不 push、不回写 Issue。 |
| C3、C4 | 动作码与原因码 | 集合写入文档由应用约束，DDL 不加 `IN` 列表，见 4.4 节。 |
| C5、C6 | 重试 | 默认值见 4.3 节；一期不提供 `FAILED` 任务的人工重试入口，唯一键不变（需要时再加 `retry_sequence`）。 |
| C7、C20 | 首次初始化 | 在该（用户，员工）首次接纳输入的入口惰性创建；先写 KV 空文档再写 SQL，见 5.4 节。授权撤销再授予不重建。 |
| C9、C10 | 既有表扩展 | `business_message`：`USER` 的 `employee_id`、`input_sequence` 非空且为正，`ASSISTANT` 为空；`business_run`：三个记忆快照列全部 `NOT NULL`；`conversation_audit_event`：五个新列可空，仅与动作码无关的成对约束。 |
| C8、C11、C12、C17、C21、C23、C24 | 会话运行资料表 | 两张表本轮都出 DDL，见 3.4 节。同 Session 同路径一个 `ACTIVE` 条目，就地递增修订，`DELETED` 墓碑；快照唯一键 `(target_session_id, source_entry_id, source_content_revision)`；`entry_path` 与框架 KV 主键同为不区分大小写；`entry_kind` 取 `PLAN`、`TASK`、`NOTE`、`GENERATED`、`OTHER`；`KV_TEXT` 来源复制内容到目标 Session 的 `snapshots/{id}`，`OBJECT_FILE` 来源只复制定位。 |
| C13、C14 | 验收与巡检 | 重新验收补三条纯 SQL 并发探针；巡检 SQL 单列一份，见 6.2、7 节。 |
| C15、C18、C19、C22 | KV 路径与写入口 | 取消 `diary/` 模板，改为 namespace + key（2.2 节）；合并水位重定向到当前代次 namespace 且不是权威（5.3 节）；一期只开放三条受控写入路径，其余原生入口默认拒绝（5.6 节）；合并候选写在 `candidates/{jobId}.md`（5.3 节）。 |
| C16 | 超时与预留 | KV 专用 DataSource 显式配置超时，`prepared_expires_at` 取预留时刻加 3 分钟，部署自检不通过则拒绝启动手工保存入口，见 5.1 节。 |

## 2. 框架 KV 映射

### 2.1 权威存储与框架建表边界

| 逻辑记录（#7 第 3 节） | 唯一权威位置 | 平台 SQL 保存什么 |
| --- | --- | --- |
| 当前整份偏好文档 `MEMORY.md` | 框架 KV 中当前代次 namespace 下的 `MEMORY.md` 记录 | `memory_profile` 保存代次、修订、观察到的 KV 版本和内容 SHA-256，用于核对；不存正文。 |
| 提炼日记 | 框架 KV 中当前代次 namespace 下的 `memory/{日期}.md` 记录 | 来源 Run 与任务标识写在日记内容里供去重；SQL 只存任务记录，不存日记正文。 |
| 记忆控制记录 | `memory_profile` | 全部元数据。 |
| 记忆更新任务 | `memory_update_job` | 全部元数据；候选结果只存 KV 定位与 SHA-256。 |
| 会话运行资料文本 | 框架 KV | `workspace_entry` 仅为索引（3.2 节）。 |
| 会话运行资料文件原件 | 私有对象存储 | `workspace_entry` 保存对象定位，规则沿用[通用素材设计](../会话运行与消息/通用素材与工具操作设计.md)。 |
| AgentState | 框架 MySQL 状态后端 | `business_session.state_session_key` 沿用既有映射。 |

框架的 `JdbcDistributedStore.create(dataSource)` 会初始化框架自带的 KV、状态等表；这些表由部署时按锁定版本核验，**不进入** `db/schema/`。`03_记忆控制建表.sql` 不创建它们，验收脚本还检查隔离库里不存在 `agentscope_store`，防止两套建表互相覆盖。记忆表与框架 KV 表是否放在同一 schema，部署时决定；放在一起不改变“二者不在同一事务”的结论。

### 2.2 KV 命名：namespace + key

框架 KV 记录由 `(namespace 列表, key)` 定位，不是单一路径字符串：框架把 namespace 各段用 `\u001f` 连成 `namespace_path`（`VARCHAR(512)`），key 为 `item_key`（`VARCHAR(255)`），主键 `(namespace_path, item_key)`。原生日记键 `memory/{日期}.md` 在 v2.0.4 源码中硬编码（2.4 节第 3 项），无法重定向到 `diary/`，所以 2026-10-22 用户确认（C15）取消原 `diary/` 模板，改为下列固定命名。`{employeeId}`、`{userId}`、`{generation}`、`{businessSessionId}` 都由服务端从可信绑定取得，不接受前端或模型提供：

```text
记忆 namespace    memory / employees / {employeeId} / users / {userId} / generations / {generation}
  key MEMORY.md                  当前文档
  key memory/{YYYY-MM-DD}.md     日记（框架原生键名，不改）
  key watermark                  合并水位（由受控包装从全局常量键重定向而来，见 5.3 节）
  key memory/.consolidation_state  水位文件副本
  key candidates/{jobId}.md      合并候选，不在 memory/ 前缀下

会话资料 namespace runtime / employees / {employeeId} / users / {userId} / sessions / {businessSessionId}
  key {entry_path}               资料条目文本
  key snapshots/{referenceId}    被引用快照（KV_TEXT 来源）
```

规则：

- namespace 中的 `generation` 就是 `memory_profile.generation` 的值，由 Run 启动时固定的 `memory_generation_at_start` 注入 `RuntimeContext`。读写只访问当前代次的 namespace，旧代次 namespace 不被读取、枚举或检索；旧代次在途 Run 继续写自己的旧 namespace，其任务因代次不符被拒绝。
- 日记按日期一个键，追加时在内容中写入 `memory_update_job.id` 与来源 Run 标识；已存在同一任务标识的追加视为已应用，不重复追加。
- 合并候选不放在 `memory/` 前缀下，原生合并器按 `*.md` 枚举 `memory` 目录时扫不到它；任务进入终态后候选不自动删除，留给 #10。该点依赖 `RemoteFilesystem` 对前缀的实际处理，待 2.4 节第 2 项验证。
- 会话资料与记忆使用不同的 namespace 起始段，互不重叠。`workspace_entry.entry_path` 的排序规则须与 KV 主键一致，见 3.4 节。
- namespace 与 key 只用于路由，不是授权依据（沿用 #7 第 2.2 节）；任何段含 `\u001f` 一律拒绝。

### 2.3 文档修订与框架 KV 版本

- `document_revision` 是平台权威计数：每次手工激活新代次时重置为 1，当前代次内每次 `MEMORY.md` 正文变化加一；日记追加不推进。
- `document_kv_version` 记录最近一次激活或合并时从框架 KV 记录读到的 `version`，**只用于交叉核对**，不参与条件更新。两者长期不一致说明有写入绕过了受控提交点，应置 `RECONCILING` 并巡检。
- `memory_profile.document_sha256` 是当前 `MEMORY.md` 全文 SHA-256。清空保存为空字节串的摘要，所以“有效空文档”（摘要等于空内容摘要且 KV 记录存在）与“记录缺失”（KV 读不到）是两种状态；后者置 `CORRUPTED`，绝不回退成默认模板。

### 2.4 框架事实核验状态

2026-10-22 对固定提交（AgentScope Java `v2.0.4`，`0b6743b263ba8e39eb417e35048026f1e0196548`）的源码取证结果。“已证实”指读到该提交的源码，**不等于**在真实实例上运行过；“仅 2.0.3”指只在本机 Maven 缓存的 2.0.3 构件中证实，须在 2.0.4 复核。

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 1 `putIfVersion` | `expectedVersion = 0` 表示仅当不存在才创建，已证实。`JdbcStore` 对 0 走普通 `INSERT`（MySQL 方言不含 `ON DUPLICATE KEY`），主键冲突返回 `false`；对大于 0 走 `UPDATE … WHERE version = ?`，影响 1 行才成功。**`false` 不等于“已存在”**：同一分支上任何 SQLSTATE 23xxx 约束错误也返回 `false`，所以必须读回核对。接口默认实现恒返回 `false`，不能假定其他 `BaseStore` 实现支持。`uploadFiles` 与 `append` 走无条件 upsert，不带版本检查。 | v2.0.4 已证实 |
| 2 namespace 与 key | 框架不拆分路径字符串：`RemoteFilesystem` 把调用方的 `filePath` 原样作 key，namespace 由 `NamespaceFactory` 按 `RuntimeContext` 单独给出，默认 namespace 为 `["agents", agentId, "users", uid, 路由段]`，不含代次与员工。`CompositeFilesystem` 是否去掉路由前缀未能确认。2.2 节的命名需据此实现自己的 `NamespaceFactory`，并验证往返。 | 仅 2.0.3；2.0.4 待核 |
| 3 日记路径 | 硬编码不可配置：`MemoryFlushManager` 123、233、234 行拼接 `MEMORY_DIR + "/" + 日期 + ".md"`，`MemoryConsolidator` 235 行 `glob("*.md", "memory")`，构造函数与 `MemoryConfig` 都没有路径入口。日期取 JVM 默认时区的系统时钟，跨午夜的一次提取可能读前一天、写后一天。因此 `diary/` 模板作废（C15）。 | v2.0.4 已证实 |
| 4 写入口 | 所有写最终经 `WorkspaceManager` 的追加或覆盖，再到 `AbstractFilesystem.uploadFiles` 与 `BaseStore.put`；追加是读-合并-写，只有进程内锁，跨副本是 last-writer-wins。入口共 5 个：`MemoryFlushMiddleware`、`CompactionMiddleware`、`ConversationCompactor`（紧急压缩）、`MemoryMaintenanceMiddleware`→`consolidate`、`MemorySaveTool`。可行的接管点是包装 `BaseStore` 或 `AbstractFilesystem` 的 `put`。被拒绝的写入在压缩路径里是静默跳过还是抛错**未验证**。 | 仅 2.0.3；2.0.4 待核 |
| 新发现 水位键 | `MemoryConsolidator` 把水位写在全局常量键 `["memory", "consolidation"]` / `watermark`（100–101、312、373 行），不经 `NamespaceFactory`；各工作区共用一个键，会跨用户、跨代次串水位，且写入不比较时间戳，是 last-writer-wins。另在 `memory/.consolidation_state` 写文件副本。 | v2.0.4 已证实 |
| 5 `JdbcStore` | 每个操作各自 `dataSource.getConnection()`、单条语句、关闭，不入平台事务；文件中没有 `setQueryTimeout`、`setNetworkTimeout` 与自动提交设置，超时完全由平台给 KV 的 DataSource 和驱动决定。MySQL 方言的 KV 表为 `namespace_path VARCHAR(512)`、`item_key VARCHAR(255)`、`value_json LONGTEXT`、`version BIGINT`、`updated_at BIGINT`，主键 `(namespace_path, item_key)`，`DEFAULT CHARSET=utf8mb4` 且**未指定排序规则**，实际取所在 schema 的默认值（本项目 schema 为 `utf8mb4_0900_ai_ci`，不区分大小写；此点为按 MySQL 8 行为的推断，部署时核验）。Connector/J 的默认 `socketTimeout` 为 0（无限）是经验推断，未在本机验证。 | v2.0.4 已证实（超时默认值除外） |

未解决、不得当作已具备：第 2、4 项在 2.0.4 上的复核；被拒绝写入的行为；全部协议在真实 `JdbcStore` 实例上的往返与竞争。

## 3. 平台控制记录

完整字段、类型、`CHECK` 与索引以 [03_记忆控制建表.sql](../../../db/schema/03_记忆控制建表.sql)、[04_会话运行资料建表.sql](../../../db/schema/04_会话运行资料建表.sql) 为准，下面只给出设计意图。

### 3.1 `memory_profile`：每个（用户，员工）一行

| 字段组 | 作用 |
| --- | --- |
| `user_id`、`employee_id` | 唯一键 `uk_user_employee`。逻辑引用，无外键，由服务层与巡检维护。 |
| `generation`、`document_revision` | 有效代次与代内修订，所有写入的条件更新都以这两个值为准。 |
| `document_kv_version`、`document_sha256` | 与框架 KV 的交叉核对值（2.3 节）。 |
| `profile_status` | `READY`、`CORRUPTED`、`RECONCILING`；非 `READY` 时暂停依赖该记忆的 Run，Session 的 `recovery_status` 不受影响。 |
| `memory_schema_version`、`policy_version` | 文档格式与策略版本，不随员工发布变化。 |
| `last_input_sequence`、`generation_input_cutoff` | 输入序号分配器与当前代次的学习起点。只有序号大于截止值的用户输入才可成为当前代次的学习来源，从而保证旧历史、导入内容不能在清空后复活。 |
| `prepared_operation_key`、`prepared_expires_at` | 两阶段中的准备预留。两者同空或同非空，由 CHECK 保证；同一时刻只有一个手工操作在准备下一代次。 |
| `last_operation_key`、`last_operation_sha256` | 最近一次已激活手工操作的去重信息：同键同摘要重试返回原结果，同键不同摘要拒绝。 |

只保存当前代次一行。旧代次内容的物理销毁与备份遗忘属于 #10；换代前后的变化靠审计事件追溯。

### 3.2 `memory_update_job`：提取与合并任务

- 唯一键 `uk_source_run_generation_task_type (source_run_id, generation, task_type)`：同一成功 Run、同一代次，提取和合并各最多一个任务；重复登记被唯一键吸收，崩溃后补扫不会重复建任务。
- `generation` 是**来源 Run 启动时捕获的代次**，不随当前代次移动；任务提交时与 `memory_profile.generation` 比较，不相等即过期。
- `source_input_sequence` 必须大于任务代次的 `generation_input_cutoff`（登记时校验，见 5.1 节清单）。偏好冲突按该序号判定，不按任务完成时间。
- `authorization_version_at_creation` 保存创建时 `platform_user.authorization_version`。领取与提交时当前值不等则任务置 `CANCELLED`。该版本是**用户级**的，用户任何员工授权变化都会取消其待处理任务，属于偏保守的取舍，宁可少学一次也不让撤销期任务复活；2026-10-21 用户确认接受。
- `candidate_locator` + `candidate_sha256` 同空或同非空；候选只供核对，不是第二份权威正文。
- `RUNNING` 必须带领取凭据与租约；终态必须有 `finished_at`，非终态必须没有；`FAILED`、`STALE`、`CANCELLED` 必须有原因码；`SUCCEEDED` 必须有应用时间，`CONSOLIDATE` 还必须有应用后的修订。

### 3.3 既有表的扩展（已并入 02 号文件）

2026-10-22 起（C2、C9、C10）下列列直接写进 [02_会话运行消息建表.sql](../../../db/schema/02_会话运行消息建表.sql)，本库为空库建表，不含已有数据迁移；已有数据的库需自行回填。

| 表 | 增加 | 约束与说明 |
| --- | --- | --- |
| `business_message` | `employee_id`、`input_sequence`（均 `BIGINT UNSIGNED NULL`） | `uk_user_employee_input_sequence (user_id, employee_id, input_sequence)`。`USER` 消息两列必须非空，`ASSISTANT` 必须为空，并入 `chk_business_message_sender`；`chk_business_message_input_order` 要求有值时大于 0。`employee_id` 必须等于所属 Session 的 `employee_id`，这是跨表关系，由服务层校验并进入巡检（6.2 节）。 |
| `business_run` | `memory_generation_at_start`、`memory_revision_at_start`、`memory_policy_version`（均 `NOT NULL`） | `chk_business_run_memory_snapshot`：数值为正、版本串非空白。创建 Run 前入口已惰性创建 `memory_profile`（C7），所以三个值一定取得到；创建后该轮不随用户改记忆而变。 |
| `conversation_audit_event` | `employee_id`、`memory_generation`、`document_revision_before`、`document_revision_after`、`memory_update_job_id`（均可空） | 只加与动作码无关的成对约束：修订前后同空同非空（`chk_conversation_audit_revision_pair`）、有修订必有代次（`…_revision_generation`）、有代次或任务必有员工（`…_memory_employee`）、有值必为正（`…_memory_positive`）。**动作码与列的对应由应用约束**（4.4 节）。注意 `MEMORY_PROFILE_INIT` 事件没有“修订前”，按上述约束只记 `memory_generation`，不记修订前后值。追加记录，不复制旧偏好正文；管理员读取记忆正文前仍须先写成功的访问审计。 |

### 3.4 会话运行资料表（[04_会话运行资料建表.sql](../../../db/schema/04_会话运行资料建表.sql)）

两张表都只保存归属、定位、修订和摘要，不含 `TEXT`/`BLOB`/`JSON` 列；文本权威在框架 KV，文件原件在私有对象存储。

- `workspace_entry`：同一 Session 内每个路径至多一个 `ACTIVE` 行，用生成列 `active_entry_path` 加 `uk_session_active_entry_path` 实现（沿用 `busy_session_id` 的做法），删除用 `DELETED` 墓碑，路径释放后可创建新行且 `content_revision` 从 1 重计（C11）。内容变化就地递增 `content_revision`，不保存历史修订；被快照或正式消息引用的内容由引用方自带不可变定位。`storage_type` 区分 `KV_TEXT`（带 `kv_version`，仅供核对，不参与条件更新；对象列必须为空；KV namespace 与 key 由 2.2 节推出，不存列）与 `OBJECT_FILE`（`storage_profile`、`object_key`、`object_version` 沿用 `message_media` 约定，`kv_version` 必须为空），两者由 `chk_workspace_entry_storage` 成对约束。`entry_path` 为 `VARCHAR(255)`、排序规则 `utf8mb4_0900_ai_ci`，与框架 KV 主键同为不区分大小写，使 `Plan.md` 与 `plan.md` 不会成为指向同一 KV 记录的两个条目；路径合法性（相对、无 `..`、无 `\u001f`）由应用校验，部署自检要核对 KV 表实际排序规则与此一致（C17）。`entry_kind` 取 `PLAN`、`TASK`、`NOTE`、`GENERATED`、`OTHER`（C23；这 5 个值是评审时按用途归的类，不是框架取值）。
- `workspace_snapshot_reference`：来源条目 ID 与固定的来源修订和摘要、来源与目标 Session、来源 Run、目标快照定位、创建人。唯一键 `(target_session_id, source_entry_id, source_content_revision)`：同一目标对同一来源同一修订只有一个快照，来源改过后再引用得到新快照（C12）。`KV_TEXT` 来源把内容复制到目标 Session namespace 的 `snapshots/{id}`，`OBJECT_FILE` 来源对象不可覆盖，只复制定位，引用同一 `object_key` 与来源固定的版本；目标 `snapshot_sha256` 必须等于来源固定摘要（`chk_workspace_snapshot_digest`）（C24）。**一期没有写入入口，表预期为空。**

两张表中 `user_id`、`employee_id` 与 Session 一致、来源与目标同属一个用户和员工，都由写入校验；DDL 不能保证。

## 4. 字段约束

### 4.1 类型与约定

- 沿用既有约定：`id/gmt_create/gmt_modified`、UTC `DATETIME(6)`、中文注释、无物理外键与存储过程、`uk_`/`idx_` 命名。状态码与标识使用 `ascii_bin`，避免默认排序规则放宽比较。
- 摘要为 `BINARY(32)`，UUID 为 `BINARY(16)`。
- 记忆表不含 `TEXT`、`BLOB`、`JSON` 列：正文与候选只在框架 KV 中，SQL 只存定位和摘要。验收脚本检查这一点。
- 字符串列的索引沿用既有写法，前缀长度等于列长（如 `task_type(16)`），即全长索引，不以截断前缀代替全长去重。

### 4.2 任务状态与允许的转移

CHECK 约束只能校验当前行，不能表达“从哪个状态转到哪个状态”。所有转移由带原状态条件的更新实现（`WHERE id = ? AND status = ?`），影响 0 行即视为竞争失败，由调用方重新读取。

| 起 → 止 | 条件 |
| --- | --- |
| `PENDING` → `RUNNING` | `next_attempt_at` 已到；设置领取凭据、租约，`attempt_count` 加一。 |
| `RUNNING` → `SUCCEEDED` | 凭据一致、代次与修订条件更新成功、授权版本相等；写应用时间。 |
| `RUNNING` → `PENDING` | 瞬时错误且未达上限；写 `reason_code`，推后 `next_attempt_at`。 |
| `RUNNING` → `FAILED` | 达到重试上限，或不可重试的错误；写 `reason_code`。 |
| `PENDING`/`RUNNING` → `STALE` | 来源代次已被取代；在激活新代次的同一事务内批量完成。 |
| `PENDING`/`RUNNING` → `CANCELLED` | 账号停用、授权版本变化等条件阻止。 |
| `RUNNING`（租约过期）→ 重新领取 | **先核对是否已应用**（读 KV 日记中的任务标识），已应用则补记 `SUCCEEDED`，不得因租约过期直接重复追加。 |

终态 `SUCCEEDED`、`FAILED`、`STALE`、`CANCELLED` 不再转移，也不改写旧行。**一期不提供 `FAILED` 任务的人工重试入口**（C6）：唯一键 `uk_source_run_generation_task_type` 会让同一 Run、同代次、同类型的新任务与旧 `FAILED` 行冲突，所以此前“新建任务”的说法在不改唯一键时不成立。用户后续的新输入会产生新的学习任务；若以后需要人工重试，须先给唯一键增加 `retry_sequence`，再重新评审入口与资格检查。

### 4.3 重试策略

| 错误类别 | 例子 | 处理 |
| --- | --- | --- |
| 瞬时 | 网络、模型限流、KV 连接失败 | 回到 `PENDING` 并退避；达到最大领取次数后 `FAILED`。 |
| 逻辑 | 代次过期、授权版本变化、来源不再合格 | 立即进入 `STALE` 或 `CANCELLED`，不重试。 |
| 结果不明 | 提交超时、应用后崩溃 | 先按任务标识与代次/修订核对已应用与否，再决定补记或重试；不盲目重做。 |

默认参数（C5），均为应用配置、不进 DDL，实际值在接入时按真实模型耗时校准：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| 最大领取次数 | 5 | 对应 `attempt_count`，达到后转 `FAILED`（原因 `RETRY_EXHAUSTED`）。 |
| 退避 | 从 30 秒起每次翻倍，上限 15 分钟，带 ±20% 抖动 | 写入 `next_attempt_at`。 |
| 租约 | 10 分钟 | 必须大于一次提取或合并的模型调用耗时；过期后先核对是否已应用再重新领取。 |

### 4.4 受控动作码与原因码（C3、C4）

两个集合都由应用枚举约束，DDL 不写 `IN` 列表，新增码不用改表。

**审计动作码**（`conversation_audit_event.action_code`，记忆与资料相关部分）：

| 动作码 | 含义 | 携带的关联列 |
| --- | --- | --- |
| `MEMORY_PROFILE_INIT` | 首次创建控制记录与空文档 | `employee_id`、`memory_generation`（不记修订前后） |
| `MEMORY_SAVE` | 用户手工保存，换代 | 代次、修订前后 |
| `MEMORY_CLEAR` | 用户手工清空，换代 | 代次、修订前后 |
| `MEMORY_AUTO_MERGE` | 自动合并推进修订 | 代次、修订前后、`memory_update_job_id` |
| `MEMORY_PROFILE_STATE_CHANGE` | 进入 `CORRUPTED` 或 `RECONCILING` | 代次 |
| `MEMORY_ADMIN_VIEW` | 固定管理员读取记忆正文或日记，须先写成功 | `employee_id` |
| `WORKSPACE_ADMIN_VIEW` | 固定管理员读取会话运行资料，须先写成功 | `employee_id`、`session_id` |

日记追加不单独审计，`memory_update_job` 行本身就是记录。

**任务原因码**（`memory_update_job.reason_code`）：

| 状态 | 原因码 |
| --- | --- |
| `STALE` | `GENERATION_SUPERSEDED` |
| `CANCELLED` | `AUTHORIZATION_CHANGED`、`ACCOUNT_DISABLED`、`SOURCE_INELIGIBLE` |
| `FAILED` | `RETRY_EXHAUSTED`、`NON_RETRYABLE_ERROR` |
| 瞬时失败（回到 `PENDING` 时记录） | `MODEL_UNAVAILABLE`、`STORE_UNAVAILABLE`、`BASELINE_CHANGED` |

## 5. 一致激活

### 5.1 手工保存/清空：两阶段（Q9 的第一类）

关键事实：框架 KV 的写入使用独立连接，**不能**与平台 SQL 事务合并。因此不追求同事务，而用“先准备不可见内容，再原子激活控制记录”：

1. **去重与预留**（SQL 事务）：若 `last_operation_key` 等于本次操作键且摘要一致，直接返回原结果；键相同摘要不同，拒绝。否则条件更新预留：`SET prepared_operation_key = ?, prepared_expires_at = ? WHERE user_id = ? AND employee_id = ? AND generation = 读取时代次 AND document_revision = 读取时修订 AND (prepared_operation_key IS NULL OR prepared_expires_at < now)`，影响 0 行表示修订已变或有他人在准备，提示用户重载。
2. **准备内容**（框架 KV，事务外）：在代次 N+1 的 namespace 下以 `putIfVersion(…, 0)` 创建 `MEMORY.md`（清空则为空文档），**无论返回值都读回核对摘要**：`true` 且摘要一致才算准备成功；`false` 可能是键已存在，也可能是别的约束错误（2.4 节第 1 项），读回后键存在且内容正是本操作写入的才可继续，否则视为被他人占用或失败。此时 `memory_profile.generation` 仍是旧值，任何读取、检索、枚举都看不到它。
3. **激活**（同一 SQL 事务）：
   - 条件更新控制记录：`SET generation = generation + 1, document_revision = 1, document_kv_version = ?, document_sha256 = ?, generation_input_cutoff = last_input_sequence, 清空预留, 写入 last_operation_*, activated_at = now WHERE 上述代次与修订 AND prepared_operation_key = 本次操作键`；
   - 把旧代次的 `PENDING`、`RUNNING` 任务批量置 `STALE`（原因 `GENERATION_SUPERSEDED`）；
   - 追加变更审计；
   - 控制记录影响 0 行则整个事务回滚。
4. 只有第 3 步提交成功后才向用户确认已保存。

失败与结果不明：

| 情形 | 处理 |
| --- | --- |
| 第 2 步失败 | 控制记录未变，原有效文档继续生效；预留到期自动释放，孤儿内容留待 #10 清理。 |
| 第 3 步提交结果不明 | 读控制记录：`last_operation_key` 等于本次操作键则已成功；否则未激活。不盲目再换一代，期间可置 `RECONCILING`。 |
| 预留过期后旧写入者晚到 | “仅当不存在才创建”使其写入失败；仍写成功的残余窗口由 `document_sha256` 读时校验发现，置 `CORRUPTED`。 |

**预留时长与超时（C16）**：框架不设任何 KV 超时，完全取决于平台给 KV 用的 DataSource。要求 KV 专用 DataSource 显式配置连接获取超时 5 秒、`socketTimeout` 15 秒、不自动重试；不接受驱动默认值。一次 KV 操作最坏约 20 秒，准备步骤（写加读回）最坏约 40 秒。`prepared_expires_at` 取预留时刻加 **3 分钟**。部署自检：`socketTimeout` 为无限，或预留时长小于 3 倍最坏准备耗时，应用都拒绝启动手工保存入口。

**未证实的前提**：以上协议依赖 `putIfVersion(…, 0)` 的语义，v2.0.4 源码已读到（2.4 节第 1 项），但没有在真实 `JdbcStore` 实例上运行过。验证前不得开放手工保存入口。

### 5.2 换代时旧任务失效（Q9 的第二类）

- 失效发生在激活事务内，不依赖后台扫描；即便批量更新漏掉某行，领取与提交时仍比较任务代次与当前代次，不等即拒绝。
- 旧代次在途 Run 可按其启动快照完成，但其产生的任务带旧代次，在登记时就会因代次不符被拒绝，不晋升到新代次。
- `generation_input_cutoff` 保证：新代次建立之前接纳的用户输入，即使其任务晚到，也因序号不大于截止值而不能成为新代次的学习来源。

### 5.3 自动合并与日记

- 自动合并只推进 `document_revision`，不换代：`UPDATE ... SET document_revision = document_revision + 1, document_kv_version = ?, document_sha256 = ? WHERE user_id = ? AND employee_id = ? AND generation = 任务代次 AND document_revision = 基线修订`。影响 0 行说明基线已变，重新读取、重新生成候选，不得无条件覆盖。
- 合并写入的 KV 记录同样先准备再激活：先把候选写到当前代次 namespace 的 `candidates/{任务 id}.md`（`candidate_locator` 只存这个 key），核对摘要后再更新控制记录与任务为 `SUCCEEDED`；中断后按任务标识核对已应用与否。候选不在 `memory/` 前缀下，终态后不自动删除，留给 #10。
- 日记追加不推进 `document_revision`，靠日记内的任务标识去重。
- **合并水位（C18）**：原生 `MemoryConsolidator` 把水位写在全局常量键（2.4 节），必须由受控 `BaseStore` 包装重写进当前（员工，用户，代次）的 namespace，key 仍为 `watermark`；文件副本 `memory/.consolidation_state` 走文件系统路径，随 namespace 自然隔离。水位只是原生合并器的内部进度，**不是权威**，权威是 `CONSOLIDATE` 任务与 `document_revision`。新代次的水位从空开始，合并器只会重读新代次自己的日记，与“清空后旧内容不复活”一致。每代次的 KV 记录清单：`MEMORY.md`、`memory/*.md`、`watermark`、`memory/.consolidation_state`、`candidates/*.md`。

### 5.4 输入序号分配

接纳正式用户输入的事务内，先 `UPDATE memory_profile SET last_input_sequence = last_input_sequence + 1 WHERE user_id = ? AND employee_id = ?`，再读取该值写入消息。同一（用户，员工）的并发接纳因行锁串行，不同用户互不影响。该锁与 Session 的活动 Run 唯一键互不替代：不同 Session 仍可并行，只是在分配序号的瞬间短暂排队。

首次初始化（C7、C20）：在该（用户，员工）**首次接纳输入的入口里惰性创建**，不挂在授权授予上；授权撤销再授予不重建，沿用原控制记录。创建只允许在该范围内尚无任何已编号的用户消息时进行；已有编号消息而控制记录缺失属于异常，置不可用并告警，不得悄悄新建（巡检见 6.2 节）。写入顺序先 KV 后 SQL：

1. 在代次 1 的 namespace 下以 `putIfVersion(…, 0)` 写空 `MEMORY.md`，读回核对摘要。
2. `INSERT memory_profile`，`document_kv_version` 取读回的版本；并发首次初始化靠 `uk_user_employee` 吸收，输的一方重新读取。
3. 崩在两步之间时，下次初始化会发现 KV 记录已存在（`putIfVersion` 返回 `false` 后读回）。若内容是空文档且摘要等于空内容摘要，就接管它；否则按异常处理，置 `CORRUPTED` 并告警，不覆盖。

### 5.5 成功 Run 登记学习任务

沿用 #7 第 6.2 节：Run 成功事务内登记待处理意图，或靠唯一键与可重复扫描补齐。登记时检查：Run 启动捕获的代次等于当前代次、来源输入序号大于截止值、账号与员工授权仍有效，并保存当时的授权版本。

### 5.6 原生写入口的开放范围（C19）

#7 第 9.3 节要求接管不了的写入口一律关闭。一期记忆写入只允许三条受控路径：成功 Run 后的 `FLUSH` 任务、`CONSOLIDATE` 任务、用户手工保存或清空。2.4 节第 4 项列出的五个原生入口默认拒绝：

| 原生入口 | 一期处理 |
| --- | --- |
| `MemoryFlushMiddleware`（调用后提取） | 不注册；学习只走成功 Run 之后基于正式消息的 `FLUSH` 任务。 |
| `CompactionMiddleware` 压缩前提取 | 注册了也由 Store 包装拒绝没有受控提交令牌的写入；压缩本身照常进行，只是不再产生记忆副作用。 |
| `ConversationCompactor` 紧急压缩提取 | 同上。 |
| `MemoryMaintenanceMiddleware` → `consolidate` | 不注册；合并只走 `CONSOLIDATE` 任务。 |
| `MemorySaveTool`（`memory_save`） | 不注册给模型。 |

代价：Run 中途被压缩掉的上下文不再被“压缩前提取”保住，只靠 Run 成功之后的学习。**未验证**：被拒绝的写入在压缩路径里是静默跳过还是抛错；若抛错，该入口必须在注册层关闭，不能依赖包装拒绝。

## 6. 评审清单

### 6.1 约束覆盖：每个决定对应什么约束

| 决定 | 数据库层约束 | 服务层责任 |
| --- | --- | --- |
| 每（用户，员工）一行 | `uk_user_employee` | 初始化条件见 5.4。 |
| 代次与修订为正 | `chk_memory_profile_generation`、`chk_memory_profile_revision` | 条件更新递增，不复用。 |
| 异常状态集合 | `chk_memory_profile_status` | 非 `READY` 时暂停依赖运行。 |
| 学习起点不超过已分配序号 | `chk_memory_profile_cutoff` | 激活时 `cutoff = last_input_sequence`。 |
| 预留同空或同非空 | `chk_memory_profile_prepare` | 过期预留可被重新预留。 |
| 手工操作去重信息成对 | `chk_memory_profile_last_operation` | 同键不同摘要拒绝。 |
| 版本字段非空白 | `chk_memory_profile_versions`、`chk_memory_job_versions` | 版本取自统一配置。 |
| 提取与合并幂等 | `uk_source_run_generation_task_type`、`chk_memory_job_task_type` | 登记时先查再插入，冲突按已登记处理。 |
| 状态集合 | `chk_memory_job_status` | 转移规则见 4.2。 |
| 运行中必须有领取信息 | `chk_memory_job_running` | 领取时同时写入。 |
| 终态与 `finished_at` 配对 | `chk_memory_job_terminal` | 终态不再转移。 |
| 终态必须有原因 | `chk_memory_job_reason` | 原因码使用受控集合。 |
| 成功必须已应用 | `chk_memory_job_success` | 合并任务写应用后的修订。 |
| 候选定位与摘要成对 | `chk_memory_job_candidate` | 候选不作权威正文。 |
| 序号与授权版本为正 | `chk_memory_job_sequence`、`chk_memory_job_authorization` | 来源序号大于截止值由登记校验。 |
| 任务可被领取与扫描 | `idx_status_next_attempt_at`、`idx_status_lease_expires_at`、`idx_user_employee_generation_status` | 批量置过期按用户、员工、代次、状态走索引。 |
| 记忆与资料表不含正文 | 四张新表无 `TEXT`/`BLOB`/`JSON` 列（验收脚本检查） | 正文只写框架 KV 或对象存储。 |
| 输入序号唯一、角色字段不混用 | `uk_user_employee_input_sequence`、`chk_business_message_sender`、`chk_business_message_input_order` | 序号在 `memory_profile` 行锁内分配；消息员工与 Session 一致由服务层校验。 |
| Run 固定记忆快照 | `chk_business_run_memory_snapshot`（列全部 `NOT NULL`） | 创建 Run 的事务读取并写入；启动代次不超过当前代次由巡检复核。 |
| 审计关联列成对 | `chk_conversation_audit_revision_pair`、`…_revision_generation`、`…_memory_employee`、`…_memory_positive` | 动作码与列的对应由应用约束（4.4 节）。 |
| 同 Session 同路径一个有效条目 | `uk_session_active_entry_path`（生成列），`entry_path` 排序规则与 KV 一致 | 路径合法性、归属一致由应用校验。 |
| 资料存储方式与定位成对 | `chk_workspace_entry_storage`、`chk_workspace_snapshot_storage` | 对象键不复用、内容摘要由写入校验。 |
| 删除墓碑 | `chk_workspace_entry_deleted` | 删除只置 `DELETED`，不物理删行。 |
| 快照幂等与摘要一致 | `uk_target_session_source_revision`、`chk_workspace_snapshot_digest` | 复制后读回计算 `snapshot_sha256`。 |

**数据库不能保证、必须由服务层保证的约束**：状态转移顺序、任务代次与当前代次相等、来源序号大于截止值、授权版本相等、两阶段的先后与读回核对、消息与 Run 所属用户和员工一致、资料来源与目标同属一个用户和员工。这些都有对应的巡检或验收场景（6.3 节），没有一项靠 CHECK 声称已保证。另有三个枚举约束（`chk_workspace_entry_storage_type`、`chk_workspace_entry_status`、`chk_workspace_snapshot_storage_type`）未做单独的拒绝探针：非法取值必然同时违反存储配对或删除标记约束，MySQL 只报其中一个，无法用同一行单独证明。

### 6.2 跨表关系：维护责任与巡检

所有关系都是逻辑引用，无物理外键，也没有触发器，由服务层写入时校验、巡检复核。可执行巡检在 [05_记忆控制关联巡检.sql](../../../db/tests/05_记忆控制关联巡检.sql)（C14）：11 项，每项返回 `check_name` 与异常数，0 表示未发现异常；下表“巡检核心条件”各自对应其中的检查，另加两项补充（序号落后于消息、消息与 Session 员工不一致）。`document_sha256` 与 KV 的核对需要读 KV，不在 SQL 范围内。

| 关系 | 维护方 | 巡检查询的核心条件 |
| --- | --- | --- |
| `memory_profile.user_id` → `platform_user.id` | 初始化入口 | 找不到对应用户的记忆控制记录。 |
| `memory_update_job.(user_id, employee_id)` → `memory_profile` | 登记入口 | 任务找不到控制记录。 |
| `memory_update_job.source_run_id` → `business_run.id` | 登记入口 | 来源 Run 不存在或状态不是 `SUCCEEDED`。 |
| `memory_update_job.source_message_id` → `business_message.id` | 登记入口 | 消息不存在、不是用户消息，或所有者与任务不一致。 |
| `memory_update_job.generation` ≤ `memory_profile.generation` | 登记与提交 | 任务代次大于当前代次，或旧代次的 `PENDING`/`RUNNING` 任务仍存在。 |
| `memory_update_job.source_input_sequence` > 其代次截止值 | 登记入口 | 序号不大于该代次的截止值。 |
| 有编号消息而无控制记录 | 受控初始化 | 某（用户，员工）存在已编号用户消息，却无 `memory_profile`。 |
| `memory_profile.document_sha256` ↔ 框架 KV 当前文档 | 读时校验与巡检 | KV 记录缺失或摘要不符。 |
| `business_run.memory_*_at_start` ↔ 控制记录 | Run 创建事务 | 启动代次大于当前代次，或修订超出当前代次的范围。旧代次的修订上限换代后不再保存，只能校验当前代次。 |
| 成功 Run 与学习任务 | 登记入口与补扫 | 成功 Run 的来源合格（当前代次、序号大于截止值）却无 `FLUSH` 任务；刚结束的 Run 有 10 分钟宽限，授权与发布资格不在此查询，命中需人工复核。 |
| 消息 `employee_id` ↔ Session | 接纳输入事务 | 用户消息的员工或用户与所属 Session 不一致，或 Session 缺失。 |
| `memory_profile.last_input_sequence` ↔ 消息 | 序号分配 | 控制记录的最后序号小于该（用户，员工）已有消息的最大序号。 |

### 6.3 恢复场景：状态与检测

对应 #7 第 8 章故障表，逐项给出落在哪个状态、靠什么发现：

| 故障或恢复情形 | 落点状态 | 检测机制 |
| --- | --- | --- |
| 记忆控制记录或当前文档缺失、摘要不符 | `profile_status = CORRUPTED` | 读取时校验摘要；巡检；有编号消息而无控制记录。 |
| 手工保存提交结果不明 | `RECONCILING` | 按 `last_operation_key` 与代次/修订核对。 |
| 记忆任务失败但业务 Run 已成功 | 任务 `FAILED`，Run 不变 | 任务状态；界面显示更新失败与原因。 |
| 租约过期、应用后崩溃 | 任务停留 `RUNNING` | 租约索引扫描；先核对日记内任务标识。 |
| 成功 Run 与任务登记之间崩溃 | 缺少任务行 | 唯一键保证重复补扫不重复登记；巡检“成功 Run 有合格来源却无任务”。 |
| 旧代次任务晚到 | `STALE` | 激活事务批量置过期；领取与提交时再比较代次。 |
| 授权撤销后再授予 | `CANCELLED` | 创建时授权版本与当前不等。 |
| 框架状态与 SQL 提交错开 | 沿用 Run `RECONCILING`、Session 暂停 | 沿用既有规则，本文不改。 |
| 备份恢复 | 联合恢复 SQL、对象与框架 KV | 恢复后核验代次、摘要与任务应用记录；删除记录与备份衔接属于 #10。 |

## 7. 验收结果与限制

### 7.1 已执行

2026-10-22，在一次性临时 MySQL 8.0.45 实例（新建数据目录、随机本机端口、空 schema，未读取登录路径，未连接任何已有库）中依次执行四份 DDL，再依次运行：旧的 03 号会话探针（确认无回归，已随 02 号文件的新增必填列同步修改）、04 号记忆控制探针、06 号扩展列与会话运行资料探针；用 07 号种子验证 05 号巡检；最后用多个并发 `mysql` 客户端进程验证三条纯 SQL 并发性质。合成数据除并发场景外全部在事务中回滚；并发场景必须提交，结束时删除并核对为空。实例随后正常关闭。

```json
{"mysql_version":"8.0.45","tables":21,"expected_rejections":85,"positive_checks":18,"inspection_checks":11,"concurrency_checks":3,"synthetic_rows_rolled_back":true,"framework_kv_tested":false,"application_integration_tested":false}
```

- 21 张表建立成功：既有 17 张、`memory_profile`、`memory_update_job`、`workspace_entry`、`workspace_snapshot_reference`；表和字段均有注释，无物理外键、无存储过程，`is_` 字段类型符合约定，四张新表无 `TEXT`/`BLOB`/`JSON` 列，隔离库中不存在 `agentscope_store`。
- 85 项拒绝（03 号探针 25、04 号 21、06 号 39）均按**预期错误码和约束/索引名称**逐项核对。06 号新增 39 项覆盖：消息输入序号重复、`USER` 缺序号或缺员工、`ASSISTANT` 带序号或员工、序号与员工为零；Run 缺记忆快照列（1364）与三种非法快照值；审计修订成对、修订缺代次、代次或任务缺员工、非正数；资料条目路径重复（含**大小写不同**的同一路径）、对象定位重复、空路径、非法用途、修订为零、非法 MIME、两种删除标记不一致、五种存储配对错误；快照唯一键、摘要不一致、修订为零、非法 MIME、四种存储配对错误。
- 18 项正向检查（03 号 4、04 号 7、06 号 7）：06 号包含另一员工可从同一序号开始、`ASSISTANT` 消息两列为空、审计新列全空的既有动作仍可写、同一 Session 外的同名路径可共存、**墓碑释放路径后同一路径可重新创建且修订重计**、来源改过后同一目标可得到新快照、索引齐全、整体回滚后无残留。
- 11 项巡检：07 号种子的基线段（一组一致的数据）上 05 号巡检全部为 0；注入异常后每一项都大于 0；最后回滚并核对为空。
- 3 项并发（多个 `mysql` 客户端进程在同一时刻起跑）：6 个客户端各 15 次并发分配同一（用户，员工）的输入序号，得到 1～90 无重复无空洞；8 个客户端争 20 个 `PENDING` 任务，每个任务恰好被领取一次；4 个客户端对 20 个控制记录带相同的代次和修订同时激活，每个恰好一个成功。作为对照，对执行器副本去掉领取的 `status = 'PENDING'` 条件、或去掉激活的代次与修订条件，两项均如预期失败（领取变成每个任务 8 次、激活变成每行 4 次）；副本已删除。
- 另重跑了既有的 [执行会话运行消息隔离验收.py](../../../db/tests/执行会话运行消息隔离验收.py)（01～02 号 DDL）：17 表、25 项拒绝、4 项正向，与 2026-10-19 记录的数字一致，02 号文件指纹已更新到[该验收记录](../会话运行与消息/通用素材与工具操作建表验收记录.md)。
- 上一轮 04 号探针首次运行曾有一项预期约束名与实际不符（拿已成功任务测任务类型约束，同时违反了成功约束，MySQL 先报后者），已用待处理任务重测；06 号探针的每一项都避免一行违反两个约束。

### 7.2 输入指纹（SHA-256，原始字节）

| 文件 | SHA-256 |
| --- | --- |
| `db/schema/01_用户与管理员建表.sql` | `D698019A35140891143DBA926500098BBD3A6511ED186BC6E5E7D4873AB1A108` |
| `db/schema/02_会话运行消息建表.sql` | `60D068E2E779D3DB6848523EC1AB0ED1B37316A1EB9C526B026392B97A5C7185` |
| `db/schema/03_记忆控制建表.sql` | `FAECE95A25DC78CCA70F3D0A0D461D13B6D818F6619DC0A64CEB685493BB5F7A` |
| `db/schema/04_会话运行资料建表.sql` | `6B4A4BA064E0DDCB0AE0A4CFF6132291821258DD8363271B7513F8C874F7AA64` |
| `db/tests/03_通用素材与工具操作建表验收.sql` | `63AA3A891658F5DFB9C8153E71CFDA5A6BFE964C794600400B24E38C38983537` |
| `db/tests/04_记忆控制建表验收.sql` | `000D86F7E2E88ED4617E8029D0F39D7CD3EE365ADEF562E70C4D08A43909E83D` |
| `db/tests/05_记忆控制关联巡检.sql` | `1DB10203AD09097963D5B8488CE316EEF285ECBD72B41839BC93A7E4A3E848C7` |
| `db/tests/06_记忆扩展与会话运行资料验收.sql` | `FD48F72ADE4780EB5FC975B2AD2827ADB9B9AEF1DEC3C2F45A0360EF57EEC7EC` |
| `db/tests/07_记忆控制巡检种子.sql` | `4FDA782C1038B24215E88240248740936E89CD9569B907E5350B081561C993A3` |
| `db/tests/执行记忆控制隔离验收.py` | `BDD7AD0668F4A45D639B1419D2FBF4C47E3FE4AA5698017BF45A629E0BE7A6E3` |

01 号 DDL 与 04 号探针的指纹与上一轮相同。**02 号 DDL、03 号 DDL 和 03 号探针的指纹已变化**：02 号并入了记忆列，03 号只改了文件头注释，03 号探针的插入语句补了新增的必填列。

### 7.3 复现入口

脚本自行创建空库，不用于迁移有数据的库。在仓库根目录执行，MySQL 安装位置不同则替换 `--mysql-bin`；Windows 下加 `PYTHONIOENCODING=utf-8` 避免错误信息乱码：

```powershell
python 'db/tests/执行记忆控制隔离验收.py' --mysql-bin 'D:\code environment\mysql\mysql-8.0.45-winx64\bin'
```

### 7.4 本结果的限制

本次**没有**验证：框架 `JdbcStore` 的读写与 `putIfVersion` 在真实实例上的语义、KV 与 SQL 之间的两阶段提交、预留过期与旧写入者竞争、租约过期后的应用核对、授权撤销再授予的并发、重启恢复、被拒绝写入在压缩路径里的行为、自定义 `NamespaceFactory` 的往返。并发探针只覆盖三条单语句的数据库性质（序号分配在行锁内、任务领取、激活条件更新），**不证明**应用层的领取循环、租约核对或事务边界正确。巡检 SQL 只在合成数据上验证了不误报、不漏报，没有对真实数据运行，也没有覆盖与框架 KV 的摘要核对。三个枚举约束没有单独的拒绝探针（6.1 节）。

## 8. 待后续决定与交付关系

- **评审中追加确认（2026-10-21）**：
  1. `business_message` 增加 `employee_id` 以支撑输入序号唯一键（3.3 节）。
  2. 日记路径是否改用框架原生 `memory/` 前缀留待接入时验证；**2026-10-22 已验证并确认（C15）：v2.0.4 源码中日记路径硬编码，2.2 节改为 namespace + key，取消 `diary/`。**
  3. 接受用户级授权版本导致的保守取消行为（3.2 节）。
- **续评审（2026-10-22）**：C1～C24 见第 1 章。
- **仍待后续**：无新的待评审设计问题。2.4 节中第 2、4 项须在 v2.0.4 上复核；全部协议须在真实 `JdbcStore` 实例与受控模型上验证，验证前不得开放手工保存/清空入口。
- **实施阶段**：实现 5 章协议、自定义 `NamespaceFactory`、受控 `BaseStore` 包装（含水位重定向与写入口拒绝）、KV 专用 DataSource 与部署自检、`memory_profile` 惰性初始化入口；用真实 `JdbcStore` 验证 5 章协议及 2.4 节各项。已有数据的库需自行回填 02 号文件新增列。
- **#10（2026-10-23 用户调整为一期后续）**：旧代次内容、孤儿准备内容、日记、合并候选、资料快照、历史对象、审计与备份的留存和物理销毁规则暂不纳入一期。新代次激活后旧 namespace 内容不会自动清除；在线隔离及恢复有效性核验仍按本文实施。
- **远端同步**：本轮早期尚未同步。2026-10-23 用户后续授权更新 GitHub 后，已回填 #9 的物理设计／SQL 证据及待启动运行交接，并更新 #1／#10、按数据库范围关闭 #12；详见[同步记录](../一期数据库验收与运行交接同步记录.md)。具体技术条款仍以本地规格为准，文件尚未提交／推送。

## 9. 2026-10-22 员工建表后的隔离回归

01 号身份 DDL 增加授权审计四列及 R01／R02 约束，02／03 号在本轮只更新员工目标表注释，04 号不变。统一隔离入口的 memory 范围已重跑：21 表、327 列、91 CHECK、85 项拒绝、18 项正向、11 项巡检、3 项 SQL 并发，合成行归零。公共执行器增加参数复用及隔离实例身份／清理保护，默认仍保留 21 表范围。

新增八表后的 full 范围共 29 表；完整基线、定向异常和新增并发的数量、限制及当前 18 个输入指纹见[全库验收记录](../数字员工配置与发布/员工配置发布授权与全库建表验收记录.md)。第 7.2 节指纹保留为当时执行证据，不代表本次变更后的输入。框架 KV 往返、两阶段激活、应用领取循环及真实重启恢复仍未验收。
