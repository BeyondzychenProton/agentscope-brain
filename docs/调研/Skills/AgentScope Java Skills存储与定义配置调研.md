# AgentScope Java Skills 存储与定义配置调研

> 项目决策更新（2026-10-20）：用户已确认[Skills MySQL 管理与版本装载设计](../../数据库设计/数字员工配置与发布/Skills_MySQL管理与版本装载设计.md)。正式 Skill 使用两张按员工发布版本固定的 MySQL 明细表，通过只读 Repository 接入；不强制先生成本地目录。本调研的框架事实保留，第二节“对本项目的选择建议”反映当时建议，具体实施以更新后的项目设计为准。

> 核对日期：2026-10-20。当前仓库是设计与原型仓库，尚无 `pom.xml` 或可运行的 AgentScope 依赖；`v2.0.4` 是[一期总体设计](../../一期总体设计.md)采用的**调研基线**，不是已经完成集成的版本。下文针对 AgentScope **Java** `v2.0.4` 标签（提交 `0b6743b263ba8e39eb417e35048026f1e0196548`）静态核对；Python 同名框架另列。源码与文档只能证明 API 和实现存在，不能代替本项目装载、恢复、隔离及发布验收。

## 一、直接结论：存储位置与装载来源是两件事

AgentScope Java 的 Skill 是一个目录，必需 `SKILL.md`，可附 `references/`、`scripts/` 等资源。`SKILL.md` 使用 YAML frontmatter，至少有非空 `name`、`description` 和正文。Skill 先作为目录、JAR 资源、Git 仓库、配置中心包或数据库记录存放；运行时由 `AgentSkillRepository` 或 Harness 工作区装载。它不是可直接调用的 Tool，模型先见名称和描述，再通过 `load_skill_through_path` 读取正文，执行动作仍依赖已配备的 Tool。[官方 Skill 文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L198-L242)、[解析校验源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/skill/util/SkillUtil.java#L63-L113)、[Tool 文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/building-blocks/tool.md#L528-L578)。

### 1. 官方 Skill 专页列出的后端

| 来源 | 实际存放和适用方式 | `v2.0.4` 依据 |
| --- | --- | --- |
| Git | 团队仓库中的 Skill 目录。`GitSkillRepository` 只读，克隆到本地目录；默认读取时检查远端 HEAD，变化才同步，也可关闭自动同步并手动 `sync()`。仓库有 `skills/` 子目录时优先用它，否则用根目录。 | [文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L247-L257)、[源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-extensions/agentscope-extensions-skills/agentscope-extensions-skill-git-repository/src/main/java/io/agentscope/core/skill/repository/GitSkillRepository.java#L40-L61) |
| Nacos | Nacos AI Skill 包按 namespace、可选版本或标签读取，适合中心化分发；仓库实现为只读。**源码限制：**构造时不传 `knownSkillNames`，`getAllSkills()` 返回空，因此仅照专页的二参数示例接 Harness，不能保证枚举到 Skill。 | [文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L258-L271)、[源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-extensions/agentscope-extensions-nacos/agentscope-extensions-nacos-skill/src/main/java/io/agentscope/core/nacos/skill/NacosSkillRepository.java#L43-L73) |
| MySQL | `MysqlSkillRepository` 使用 Skill 主表及资源表，主表存名称、描述、正文、来源和可选完整 metadata，资源表存相对路径及内容；可读写，`writeable(false)` 可只读分发。**它自建的两张表不等于本项目的员工发布快照表。**默认主表按 `name` 唯一，不能把它本身当作本项目发布版本模型。 | [文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L272-L285)、[建表源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-extensions/agentscope-extensions-skills/agentscope-extensions-skill-mysql-repository/src/main/java/io/agentscope/core/skill/repository/mysql/MysqlSkillRepository.java#L290-L334) |
| Classpath | `src/main/resources/skills/<name>/SKILL.md` 随 JAR 发布，运行时只读；适合随程序一起更新、无需后台编辑的内置能力，兼容普通 JAR 和 Spring Boot Fat JAR。 | [文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L286-L297)、[源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/skill/repository/ClasspathSkillRepository.java#L203-L205) |

源码还提供两种专页未列入上述四种示例的实现：

- **通用本地目录：**`FileSystemSkillRepository(Path baseDir, boolean writeable)` 从每个含 `SKILL.md` 的子目录读取，可选读写、延迟加载资源。这可用于 `ReActAgent.builder().skillRepository(...)` 等显式挂载；Harness 自己还有 Workspace 装载层，二者不要重复计数。[实现](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/skill/repository/FileSystemSkillRepository.java#L38-L49)、[构造与读取](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/skill/repository/FileSystemSkillRepository.java#L91-L124)、[Core 文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/building-blocks/tool.md#L514-L525)。
- **PostgreSQL：**`PostgresSkillRepository` 的数据结构与 MySQL 类似，也有 Skills 与 Resources 表、CRUD 和可选 `metadata_json`。这是 `v2.0.4` 源码存在的扩展，不能因为 Skill 专页只举 MySQL 就说框架不支持 PostgreSQL。[实现与表结构](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-extensions/agentscope-extensions-skills/agentscope-extensions-skill-postgresql-repository/src/main/java/io/agentscope/core/skill/repository/postgresql/PostgresSkillRepository.java#L40-L123)。

此外，`AgentSkillRepository` 是可自实现的仓库接口；源码里的平台服务即以 `DefinitionStoreSkillRepository` 从定义存储读取 Skill。这里的“自定义后端”指扩展接口，不意味着框架提供现成的对象存储 Skill 仓库。[仓库接口](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/skill/repository/AgentSkillRepository.java#L35-L121)、[官方服务实现](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-service/service-common/src/main/java/io/agentscope/builder/web/catalog/DefinitionStoreSkillRepository.java#L33-L85)。

### 2. Harness 工作区与物理后端

`HarnessAgent` 不注册仓库也能从 `workspace/skills/<name>/` 读共享 Skill；传入可信的 `RuntimeContext.userId` 后，还可从用户逻辑目录 `<userId>/skills/<name>/` 读用户覆盖版。Skill 目录的**逻辑结构**不等于固定的本机磁盘路径：`AbstractFilesystem` 可落本地磁盘、共享 KV（如 Redis/JDBC）或沙箱映射；用户级资源读取也走当前上下文的文件系统命名空间。[工作区文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L309-L343)、[WorkspaceSkillRepository 源码](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/skill/WorkspaceSkillRepository.java#L47-L68)、[工作区物理后端](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/workspace.md#L257-L281)。

同名 Skill 的覆盖顺序由低到高为：`projectGlobalSkillsDir(Path)` → 按注册顺序的各 `skillRepository(...)` → `workspace/skills/` → `<userId>/skills/`；低层独有的 Skill 保留。`skillRepository(...)` 可多次调用。默认每次调用前重新合成可见目录，`disableDynamicSkills()` 才改为构建时合成一次；“动态重新合成”并不自动给本项目正在运行的 Session 保证发布版本不漂移。[优先级与 Builder 选项](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L344-L369)、[Harness Builder](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java#L1746-L1779)。

市场仓库中的资源先读进内存；若需要 shell 执行脚本，Harness 会在调用前将其物化至工作区 `.skills-cache/`，沙箱模式再投影到容器。**读取 `SKILL.md` 不要求启用 shell**，脚本执行则必须有对应工具与文件可达性。本项目一期明确不开放任意命令、脚本或容器执行，因此会议 Skill 可只含受控文本与参考资料，不应凭 `scripts/` 目录格式推导为已授权执行。[资源加载与物化](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L421-L470)、[读取和执行边界](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L524-L530)、[本项目一期边界](../../一期总体设计.md#5-skills工具与写操作确认)。

## 二、推荐的 Skill 定义与配置形式

官方最直接推荐的是**目录 + `SKILL.md`**，`name` 和 `description` 放 YAML frontmatter，正文写触发后的操作指引；长文放 `references/`，脚本若被允许则放 `scripts/`。`description` 应写清“用户在什么场景需要它”，因为模型初始只见名称和描述；官方建议正文控制在约 2k tokens，内部引用用相对路径。通用能力由市场统一维护，项目特有规则放工作区，用户目录用于覆盖或补充。[格式示例](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L210-L230)、[官方写作建议](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/skill.md#L531-L542)。

以下是**结构示意，不是会议业务方确认的流程**：

```text
meeting-room-reservation/
├── SKILL.md
└── references/
    └── 会议规则.md
```

```markdown
---
name: meeting-room-reservation
description: 当用户要查询会议室可用时段、发起预约或核对预约结果时使用。
---

# 会议室预约

1. 读取 `references/会议规则.md`，确认业务字段和查询步骤。
2. 使用已授权的查询工具取得候选会议室和时段。
3. 需要写入预约时，按平台确认流程提交；以会议系统返回及核验结果为准。
```

Java 也允许通过 `AgentSkill` 显式参数或 builder 在代码中组装 metadata、正文和资源，或从 `.skill`/`.zip` 包解析；这属于**程序化构造与打包入口**，不改变最终的 Skill 语义。`name`、`description`、正文在解析时均会校验非空。工作区文档提到与文件等价的 API 声明，但在本标签源码中核到的明确装载入口是 `skillRepository(...)`；实施时应按实际固定依赖 API 编译验证，不直接照抄未核实的 `.skill(SkillDeclaration...)` 叙述。[AgentSkill 构造](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/skill/AgentSkill.java#L39-L61)、[Zip 解析](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-core/src/main/java/io/agentscope/core/skill/util/SkillUtil.java#L115-L159)、[工作区 API 叙述](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/harness/workspace.md#L209-L220)、[实际 Harness Builder](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java#L1746-L1779)。

### 对本项目的选择建议（推论，不是框架默认）

当前已确认的配置发布规格要求把 Skill 正文、全部受控静态文件、文件清单和摘要固定进**不可变员工发布版本**；文本可保存在平台 SQL 快照，非文本资源可用私有对象存储，再由该固定版本投影到 AgentScope Workspace。以此为准，一期宜先采用完整 Skill 包随员工版本固化、运行时投影装载的路径；运行投影需要按员工与发布版本隔离，不能让旧 Session 读取被新版覆盖的同一路径。直接连接可更新的 Git HEAD、Nacos 最新标签或单份按名称覆盖的 MySQL Skill 表，均需另外解决旧 Session 的版本固定问题。MySQL Skill 仓库可以作为后续共享目录/分发后端，但其框架表不能替代平台发布事实。[本项目发布快照规格](../../数据库设计/数字员工配置与发布/数字员工配置发布与授权数据模型.md#L45-L57)、[Git 更新行为](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-extensions/agentscope-extensions-skills/agentscope-extensions-skill-git-repository/src/main/java/io/agentscope/core/skill/repository/GitSkillRepository.java#L40-L51)、[MySQL 表唯一键](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/agentscope-extensions/agentscope-extensions-skills/agentscope-extensions-skill-mysql-repository/src/main/java/io/agentscope/core/skill/repository/mysql/MysqlSkillRepository.java#L50-L72)。

Skill 只给 Agent 提供方法说明，不授予 Tool 权限。会议 MCP 的真实调用身份、工具白名单、写操作确认、幂等和审计继续由平台执行；Skill 包不保存密钥。[框架 Tool/Skill 边界](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.4/docs/v2/zh/docs/building-blocks/tool.md#L528-L578)、[本项目一期设计](../../一期总体设计.md#5-skills工具与写操作确认)、[配置快照保密边界](../../数据库设计/数字员工配置与发布/数字员工配置发布与授权数据模型.md#L55-L59)。

## 三、高星生态项目的旁证（产品做法，不是 Java 原生后端）

| 项目 | 观察到的 Skill 管理方式 | 可借鉴之处与边界 |
| --- | --- | --- |
| [QwenPaw](https://github.com/agentscope-ai/QwenPaw) | 官方文档把共享 `skill_pool/` 与每个 Agent 的 `workspaces/{agent_id}/skills/` 运行副本分开，`skill.json` 管池/工作区清单，工作区副本才是运行时实际加载内容；还提供广播、同步、启停与导入界面。[项目文档](https://github.com/agentscope-ai/QwenPaw/blob/main/website/public/docs/skills.en.md#L195-L242)、[工作区行为](https://github.com/agentscope-ai/QwenPaw/blob/main/website/public/docs/skills.en.md#L299-L339) | 展示“共享素材池 → 员工/工作区副本”的产品设计。`skill.json` 属 QwenPaw 产品配置，不能说是 AgentScope Java 强制的 Skill 格式。 |
| [AgentTeams](https://github.com/agentscope-ai/AgentTeams) | 官方 Worker 指南要求先把完整 Skill 包写进 Worker 的持久对象存储，再更新 `Worker.spec.skills` 分配记录，随后同步加载并验证实际可用性。[分发顺序](https://github.com/agentscope-ai/AgentTeams/blob/main/docs/usage/worker-guide.md#L246-L269)、[持久目录与验证](https://github.com/agentscope-ai/AgentTeams/blob/main/docs/usage/worker-guide.md#L298-L304) | 展示“先持久化完整包，再声明分配，再验运行态”的交付顺序。AgentTeams 可启动多种 Worker runtime，这套对象存储与 `spec.skills` 是产品机制，不能当作 Java Harness 的现成 Skill 后端。[runtime 列表](https://github.com/agentscope-ai/AgentTeams/blob/main/docs/usage/worker-guide.md#L366-L375) |

这两个仓库与 AgentScope 同属 `agentscope-ai` 组织；2026-10-20 读取 GitHub API 时，[QwenPaw](https://api.github.com/repos/agentscope-ai/QwenPaw) 约 3.6 万 Star，[AgentTeams](https://api.github.com/repos/agentscope-ai/AgentTeams) 约 5.8 千 Star（数字会变化）。其公开文档用于观察产品层做法，不作为 Java `v2.0.4` API 证明；Star 数也不证明设计正确性或兼容性。

## 四、Python 同名框架的版本边界

AgentScope Python `2.0.4` 的[官方 Tool 文档](https://docs.agentscope.io/en/versions/2.0.4/building-blocks/tool#skill)让 `Toolkit(skills_or_loaders=[...])` 接目录路径、`Skill` 对象或 `SkillLoaderBase`，示例是 `LocalSkillLoader`；它也采用 `SKILL.md`，但加载 API 是 Python `Toolkit` 与 `Skill` 查看器。不能把 Java 的 `HarnessAgent.skillRepository(...)`、四层优先级或 `MysqlSkillRepository` 直接套到 Python。[Python 装载示例](https://docs.agentscope.io/en/versions/2.0.4/building-blocks/tool#register-skill)、[Python Skill 工作方式](https://docs.agentscope.io/en/versions/2.0.4/building-blocks/tool#how-skill-works)。

## 五、后续实施需要验证的具体点

1. 固定实际 AgentScope Java 依赖版本后，验证发布快照完整投影、`SKILL.md`/参考资料的可读性与重启恢复；此仓库尚未运行 Java 集成测试。
2. 以两个用户、两个员工、旧新发布版本构造真实调用，确认同名 Skill 的优先级、Workspace 命名空间与旧 Session 的版本固定；不能只以文件存在判定运行可见。
3. 若采用 MySQL/Nacos/Git 市场后端，再验证对应仓库的枚举、更新时机、资源完整性与下架行为；Nacos 的 `knownSkillNames` 尤其要显式测试。
4. 会议 Skill 使用 MCP 工具时仍须走平台授权、确认、身份与结果核对链；Skill 内容审核不替代这些业务验收。
